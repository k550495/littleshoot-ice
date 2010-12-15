package org.lastbamboo.common.ice;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import org.lastbamboo.common.ice.candidate.IceCandidate;
import org.lastbamboo.common.ice.candidate.IceCandidateVisitor;
import org.lastbamboo.common.ice.candidate.IceCandidateVisitorAdapter;
import org.lastbamboo.common.ice.candidate.IceTcpHostPassiveCandidate;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoder;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoderImpl;
import org.lastbamboo.common.offer.answer.OfferAnswer;
import org.lastbamboo.common.offer.answer.OfferAnswerListener;
import org.lastbamboo.common.portmapping.NatPmpService;
import org.lastbamboo.common.portmapping.PortMapListener;
import org.lastbamboo.common.portmapping.PortMappingProtocol;
import org.lastbamboo.common.portmapping.UpnpService;
import org.lastbamboo.common.stun.stack.StunAddressProvider;
import org.lastbamboo.common.util.CandidateProvider;
import org.lastbamboo.common.util.NetworkUtils;
import org.lastbamboo.common.util.RuntimeIoException;
import org.lastbamboo.common.util.ThreadUtils;
import org.littleshoot.mina.common.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OfferAnswer} handler for TCP connections.
 */
public class TcpOfferAnswer implements IceOfferAnswer, 
    StunAddressProvider, PortMapListener {

    private final Logger m_log = LoggerFactory.getLogger(getClass());
    private final AtomicReference<Socket> m_socketRef = 
        new AtomicReference<Socket>();
    private Collection<IceCandidate> m_candidates;
    private final boolean m_controlling;
    private final OfferAnswerListener m_offerAnswerListener;
    private final InetAddress m_publicAddress;
    private final NatPmpService m_natPmpService;
    private final UpnpService m_upnpService;
    private final MappedTcpAnswererServer m_answererServer;
    
    private ServerSocket m_serverSocket;
    private int m_natPmpMappingIndex;
    private int m_upnpMappingIndex;
    private boolean m_portMapError = false;
    private int m_mappedPort;

    /**
     * Creates a new TCP {@link OfferAnswer} class for processing offers and
     * answers for creating a TCP connection to a remote peer.
     * @param publicAddress The public address for this host.
     * 
     * @param offerAnswerListener The class to notify of sockets.
     * @param controlling Whether or not we're the controlling side of the
     * connection.
     * @param answererServer The class that has a router-mapped port for 
     * the answering server socket. 
     * @param stunCandidateProvider Provider for STUN addresses.
     */
    public TcpOfferAnswer(final InetAddress publicAddress,
        final OfferAnswerListener offerAnswerListener,
        final boolean controlling, final NatPmpService natPmpService,
        final UpnpService upnpService,
        final MappedTcpAnswererServer answererServer, 
        final CandidateProvider<InetSocketAddress> stunCandidateProvider) {
        this.m_publicAddress = publicAddress;
        this.m_offerAnswerListener = offerAnswerListener;
        this.m_controlling = controlling;
        this.m_natPmpService = natPmpService;
        this.m_upnpService = upnpService;
        this.m_answererServer = answererServer;
        
        // We only start another server socket on the controlling candidate
        // because the non-controlled, answering agent simply uses the same
        // server socket across all ICE sessions, forwarding their data to
        // the HTTP server.
        if (controlling || answererServer == null) {
            try {
                this.m_serverSocket = new ServerSocket();
                this.m_serverSocket.bind(
                    new InetSocketAddress(NetworkUtils.getLocalHost(), 0));

                // Accept incoming sockets on a listening thread.
                listen();
                m_log.info("Starting offer answer");
            } catch (final IOException e) {
                m_log.error("Could not bind server socket", e);
                throw new RuntimeIoException("Could not bind server socket", e);
            }
        }
    }

    public void close() {
        m_log.info("Closing!!");
        final Socket sock = m_socketRef.get();
        if (sock != null) {
            try {
                sock.close();
            } catch (final IOException e) {
                m_log.info("Exception closing socket", e);
            }
        }

        if (this.m_serverSocket != null) {
            try {
                this.m_serverSocket.close();
            } catch (final IOException e) {
                m_log.info("Exception closing server socket", e);
            }

            // We need to unmap any ports we've mapped, and we've only mapped
            // ports if the server socket is not null.
            if (this.m_upnpMappingIndex != -1) {
                this.m_upnpService.removeUpnpMapping(this.m_upnpMappingIndex);
            }
            if (this.m_natPmpMappingIndex != -1) {
                this.m_natPmpService.removeNatPmpMapping(
                    this.m_natPmpMappingIndex);
            }
        }
    }

    public void closeTcp() {
        close();
    }

    public void closeUdp() {
        // Ignored.
    }

    private void listen() {
        final InetSocketAddress socketAddress = 
            (InetSocketAddress) m_serverSocket.getLocalSocketAddress();

        // Our basic strategy here is to just go ahead and try to map the ports
        // without paying much attention to their success or failure. If they
        // work, we'll connect to them during ICE. If the mapping doesn't work,
        // those connection attempts should just fail quickly.
        final int localPort = socketAddress.getPort();
        
        // Due to the asynchronous nature of the port mapping calls, we assume
        // that the router will map to the same port as the local port, as 
        // we request. If we get a notification the router has assigned a 
        // different port by the time we send out the candidates, we of course
        // record that.
        this.m_mappedPort = localPort;
        this.m_natPmpMappingIndex = this.m_natPmpService.addNatPmpMapping(
            PortMappingProtocol.TCP, localPort, localPort, this);
        this.m_upnpMappingIndex = this.m_upnpService.addUpnpMapping(
            PortMappingProtocol.TCP, localPort, localPort, this);
        final Runnable serverRunner = new Runnable() {
            public void run() {
                // We just accept the single socket on this port instead of
                // the typical "while (true)".
                try {
                    m_log.info("Waiting for incoming socket on: {}",
                            socketAddress);
                    final Socket sock = m_serverSocket.accept();
                    m_log.info("Got incoming socket from "+
                        sock.getRemoteSocketAddress() +"!! Controlling: {}",
                        m_controlling);
                    onSocket(sock);
                } catch (final IOException e) {
                    m_log.info("Exception accepting socket!!", e);
                }
            }
        };
        final Thread serverThread = new Thread(serverRunner,
            "TCP-Ice-Server-Thread-" + hashCode() +": " + socketAddress);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public byte[] generateAnswer() {
        // TODO: This is a little bit odd since the TCP side should
        // theoretically generate the SDP for the TCP candidates.
        final String msg = 
            "We fallback to the old code for gathering this for now.";
        m_log.error("TCP implemenation can't generate offers or answers");
        throw new UnsupportedOperationException(msg);
    }

    public byte[] generateOffer() {
        // TODO: This is a little bit odd since the TCP side should
        // theoretically generate the SDP for the TCP candidates.
        final String msg = 
            "We fallback to the old code for gathering this for now.";
        m_log.error("TCP implemenation can't generate offers or answers");
        throw new UnsupportedOperationException(msg);
    }

    public void processOffer(final ByteBuffer offer) {
        processRemoteCandidates(offer);
    }

    public void processAnswer(final ByteBuffer answer) {
        // We don't need to do any processing if we've already got the socket.
        if (this.m_socketRef.get() != null) {
            m_log.info("Controlling side already has a socket -- "
                    + "ignoring answer.");
            return;
        }
        processRemoteCandidates(answer);
    }

    private void processRemoteCandidates(final ByteBuffer encodedCandidates) {
        final IceCandidateSdpDecoder decoder = new IceCandidateSdpDecoderImpl();
        final Collection<IceCandidate> remoteCandidates;
        try {
            // Note the second argument doesn't matter at all.
            remoteCandidates = decoder.decode(encodedCandidates, m_controlling);
        } catch (final IOException e) {
            m_log.warn("Could not process remote candidates", e);
            return;
        }

        // OK, we've got the candidates. We'll now parallelize connection
        // attempts to all of them, taking the first to succeed. Note there's
        // typically a single local network candidate that will only succeed
        // if we're on same subnet and then a public candidate that's
        // either there because the remote host is on the public Internet or
        // because the public address was mapped using UPnP.
        final IceCandidateVisitor<Object> visitor = 
            new IceCandidateVisitorAdapter<Object>() {
            @Override
            public Object visitTcpHostPassiveCandidate(
                    final IceTcpHostPassiveCandidate candidate) {
                m_log.info("Visiting TCP passive host candidate: {}", candidate);
                return connectToCandidate(candidate);
            }
        };
        for (final IceCandidate candidate : remoteCandidates) {
            candidate.accept(visitor);
        }
    }

    private Object connectToCandidate(final IceCandidate candidate) {
        if (candidate == null) {
            m_log.warn("Null candidate?? " + ThreadUtils.dumpStack());
            return null;
        }
        final Runnable threadRunner = new Runnable() {
            public void run() {
                Socket sock = null;
                try {
                    m_log.info("Connecting to: {}", candidate);
                    sock = new Socket();
                    sock.connect(candidate.getSocketAddress(), 16 * 1000);
                    m_log.info("Client socket connected to: {}", candidate);
                    onSocket(sock);
                    // Close this at the end in case it throws an exception.
                } catch (final IOException e) {
                    m_log.info("IO Exception connecting", e);
                }

                // If we've successfully created an outgoing socket, we need
                // to close the listening socket *if we're the controlling
                // agent*. If we're not the controlling agent, we wait for the
                // other side to close all sockets.
                if (m_controlling && sock != null && m_serverSocket != null) {
                    try {
                        m_serverSocket.close();
                    } catch (final IOException e) {
                        // We don't really care if this fails.
                    }
                }
            }
        };
        final Thread connectorThread = new Thread(threadRunner,
                "ICE-TCP-Connect-For-Candidate-" + candidate);
        connectorThread.setDaemon(true);
        connectorThread.start();
        return null;
    }

    /**
     * Provides unified socket handler for both incoming and outgoing sockets.
     * This primarily checks to see if a socket has already been set, closing
     * the new one if so.
     * 
     * @param sock The socket to process;
     */
    private void onSocket(final Socket sock) {
        // this.m_offerAnswerListener.onTcpSocket(sock);
        if (m_socketRef.compareAndSet(null, sock)) {
            this.m_offerAnswerListener.onTcpSocket(sock);
        }

        else {
            m_log.info("Socket already exists! Ignoring second");
            // If a socket already existed, we close the socket *only if we're
            // the controlling peer*. Otherwise, it's possible for there to be 
            // a race condition where each side both possible successful 
            // sockets in rapid succession, causing both sides to close 
            // the second socket they receive, ultimately closing all 
            // successful sockets!!
            if (this.m_controlling) {
                m_log.info("Closing on controlling candidate");
                try {
                    sock.close();
                } catch (final IOException e) {
                    m_log.error("Could not close socket", e);
                }
            } else {
                m_log.info("Not closing on controlled candidate");

                // We also need to notify the listener a new socket has come in.
                // The controlling side will take care of closing it.
                this.m_offerAnswerListener.onTcpSocket(sock);
            }
        }
    }

    public Collection<? extends IceCandidate> gatherCandidates() {
        if (m_candidates != null) {
            return m_candidates;
        }
        final Collection<IceCandidate> candidates = 
            new ArrayList<IceCandidate>();

        // Only add the TURN candidate on the non-controlling side to save
        // resources. This is non-standard as well, but we should only need
        // one TURN server per session.
        // if (!this.m_controlling && !NetworkUtils.isPublicAddress())
        // {
        // addTcpTurnCandidate(client, candidates);
        // }

        // Add the host candidate. Note the host candidate is also used as
        // the BASE candidate for the server reflexive candidate below.
        final InetSocketAddress hostAddress = getHostAddress();

        final IceCandidate hostCandidate = new IceTcpHostPassiveCandidate(
            hostAddress, this.m_controlling);
        candidates.add(hostCandidate);

        // OK, the following is non-standard. If we have a public address
        // for the host from our UDP STUN check, we use the address part for
        // a new candidate because we always make an effort to map our TCP
        // host port with UPnP. This is not a simultaneous open candidate,
        // although there may be cases where this actually succeeds when UPnP
        // mapping failed due to simultaneous open behavior on the NAT.
        if (this.m_publicAddress != null && hostPortMapped()) {
            m_log.info("Adding public TCP address");
            // We're not completely sure if the port has been mapped yet at
            // this point. We know there hasn't been an error, but that's 
            // about it. The mapped port will actually be the port mapped
            // on the router in two cases:
            // 1) We've received an alert about the mapped port
            // 2) We've haven't received an alert, but the router has in fact
            // mapped the local port to the same port on the router as we 
            // requested. That mapping should theoretically work by the time
            // the external side tries to use it.
            final InetSocketAddress publicHostAddress = new InetSocketAddress(
                this.m_publicAddress, this.m_mappedPort);

            final IceCandidate publicHostCandidate = 
                new IceTcpHostPassiveCandidate(publicHostAddress, 
                    this.m_controlling);
            candidates.add(publicHostCandidate);
        } else{
            m_log.info("Not adding public. PA: "+m_publicAddress+" mapped: "+
                hostPortMapped());
        }
        m_candidates = candidates;
        return m_candidates;
    }

    public InetSocketAddress getHostAddress() {
        if (m_serverSocket != null) {
            return 
                (InetSocketAddress) this.m_serverSocket.getLocalSocketAddress();
        } else if (this.m_answererServer != null) {
            m_log.info("Accessing host address from mapped TCP server.");
            return this.m_answererServer.getHostAddress();
        }
        return null;
    }

    public InetSocketAddress getRelayAddress() {
        return null;
    }

    public InetSocketAddress getServerReflexiveAddress() throws IOException {
        return null;
    }

    public InetAddress getStunServerAddress() {
        return null;
    }

    public boolean hostPortMapped() {
        return !m_portMapError;
    }

    public InetAddress getPublicAdress() {
        return this.m_publicAddress;
    }

    public void useRelay() {
        // The controlling code decided to use the relay. Nothing to do here.
    }

    public void onPortMap(final int externalPort) {
        m_log.info("Received port maped: {}", externalPort);
        this.m_mappedPort = externalPort;
    }

    public void onPortMapError() {
        m_log.info("Got port map error.");
        this.m_portMapError = true;
    }
}
