package org.lastbamboo.common.ice;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.lastbamboo.common.ice.candidate.IceCandidate;
import org.lastbamboo.common.ice.candidate.IceCandidateVisitor;
import org.lastbamboo.common.ice.candidate.IceCandidateVisitorAdapter;
import org.lastbamboo.common.ice.candidate.IceTcpHostPassiveCandidate;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoder;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoderImpl;
import org.lastbamboo.common.offer.answer.OfferAnswer;
import org.lastbamboo.common.offer.answer.OfferAnswerListener;
import org.lastbamboo.common.stun.client.PublicIpAddress;
import org.littleshoot.mina.common.ByteBuffer;
import org.littleshoot.stun.stack.StunAddressProvider;
import org.littleshoot.util.CandidateProvider;
import org.littleshoot.util.PublicIp;
import org.littleshoot.util.RuntimeIoException;
import org.littleshoot.util.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OfferAnswer} handler for TCP connections.
 */
public class TcpOfferAnswer<T> implements IceOfferAnswer, 
    StunAddressProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final AtomicReference<Socket> socketRef = 
        new AtomicReference<Socket>();
    private final boolean controlling;
    private final OfferAnswerListener<T> offerAnswerListener;
    
    private final MappedTcpOffererServerPool offererServer;
    private PortMappedServerSocket portMappedServerSocket;
    private final MappedServerSocket mappedServerSocket;
    private final SocketFactory socketFactory;
    
    
    private static final ExecutorService tcpIceServerThreadPool = 
        Executors.newCachedThreadPool(new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, 
                    "TCP-Ice-Server-Thread-" + hashCode() +"-"+count);
                t.setDaemon(true);
                count++;
                return t;
            }
        });

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
    public TcpOfferAnswer(
        final OfferAnswerListener<T> offerAnswerListener,
        final boolean controlling, 
        final MappedServerSocket answererServer, 
        final CandidateProvider<InetSocketAddress> stunCandidateProvider,
        final MappedTcpOffererServerPool offererServer,
        final SocketFactory socketFactory) {
        this.offerAnswerListener = offerAnswerListener;
        this.controlling = controlling;
        this.offererServer = offererServer;
        this.socketFactory = socketFactory;
        
        // We only start another server socket on the controlling candidate
        // because the non-controlled, answering agent simply uses the same
        // server socket across all ICE sessions, forwarding their data to
        // the HTTP server.
        if (controlling || answererServer == null) {
            log.info("Using pooled offerer server");
            try {
                this.portMappedServerSocket = offererServer.serverSocket();
                this.mappedServerSocket = portMappedServerSocket;

                // Accept incoming sockets on a listening thread.
                listen();
                log.info("Starting offer answer");
            } catch (final IOException e) {
                log.error("Could not bind server socket", e);
                throw new RuntimeIoException("Could not bind server socket", e);
            }
        } else {
            log.info("Using mapped server socket");
            this.mappedServerSocket = answererServer;
        }
    }

    public void close() {
        log.info("Closing!!");
        final Socket sock = socketRef.get();
        if (sock != null) {
            try {
                sock.close();
            } catch (final IOException e) {
                log.info("Exception closing socket", e);
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
        final ServerSocket ss = this.portMappedServerSocket.getServerSocket();
        final InetSocketAddress socketAddress = 
            (InetSocketAddress) ss.getLocalSocketAddress();

        if (ss instanceof SSLServerSocket) {
            log.info("Enabled cipher suites on SSL server socket: {}", 
                Arrays.asList(((SSLServerSocket)ss).getEnabledCipherSuites()));
        }
        final Runnable serverRunner = new Runnable() {
            public void run() {
                // We just accept the single socket on this port instead of
                // the typical "while (true)".
                try {
                    log.info("Waiting for incoming socket on: {}",
                            socketAddress);
                    final Socket sock = ss.accept();
                    
                    // Just for debugging.
                    if (sock instanceof SSLSocket) {
                        final SSLSocket ssl = (SSLSocket) sock;
                        log.info("Enabled cipher suites on accepted " +
                            "server socket: {}", 
                            Arrays.asList(ssl.getEnabledCipherSuites()));
                    }

                    log.info("GOT INCOMING SOCKET FROM "+
                        sock.getRemoteSocketAddress() +"!! Controlling: {}",
                        controlling);
                    sock.setKeepAlive(true);
                    onSocket(sock);
                } catch (final IOException e) {
                    // This could also be a socket timeout because we limit
                    // the length of time allowed on accept calls.
                    log.info("Exception accepting socket. This will often " +
                        "happen when the client side connects first, and we " +
                        "simply return the socket back to the pool.", e);
                } finally {
                    // Adding back server socket. Note this is fine to do no
                    // matter what actual processing happened with the socket
                    // just received, as that's just an independent Socket, 
                    // while the ServerSocket is available for more connections.
                    offererServer.addServerSocket(portMappedServerSocket);
                }
            }
        };
        tcpIceServerThreadPool.execute(serverRunner);
    }

    public byte[] generateAnswer() {
        // TODO: This is a little bit odd since the TCP side should
        // theoretically generate the SDP for the TCP candidates.
        final String msg = 
            "We fallback to the old code for gathering this for now.";
        log.error("TCP implemenation can't generate offers or answers");
        throw new UnsupportedOperationException(msg);
    }

    public byte[] generateOffer() {
        // TODO: This is a little bit odd since the TCP side should
        // theoretically generate the SDP for the TCP candidates.
        final String msg = 
            "We fallback to the old code for gathering this for now.";
        log.error("TCP implemenation can't generate offers or answers");
        throw new UnsupportedOperationException(msg);
    }

    public void processOffer(final ByteBuffer offer) {
        processRemoteCandidates(offer);
    }

    public void processAnswer(final ByteBuffer answer) {
        // We don't need to do any processing if we've already got the socket.
        if (this.socketRef.get() != null) {
            log.info("Controlling side already has a socket -- "
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
            remoteCandidates = decoder.decode(encodedCandidates, controlling);
        } catch (final IOException e) {
            log.warn("Could not process remote candidates", e);
            return;
        }

        // OK, we've got the candidates. We'll now parallelize connection
        // attempts to all of them, taking the first to succeed. Note there's
        // typically a single local network candidate that will only succeed
        // if we're on same subnet and then a public candidate that's
        // either there because the remote host is on the public Internet or
        // because the public address was mapped using UPnP or NAT-PMP.
        final IceCandidateVisitor<Object> visitor = 
            new IceCandidateVisitorAdapter<Object>() {
            @Override
            public Object visitTcpHostPassiveCandidate(
                    final IceTcpHostPassiveCandidate candidate) {
                log.info("Visiting TCP passive host candidate: {}", candidate);
                return connectToCandidate(candidate);
            }
        };
        for (final IceCandidate candidate : remoteCandidates) {
            candidate.accept(visitor);
        }
    }

    private Object connectToCandidate(final IceCandidate candidate) {
        if (candidate == null) {
            log.warn("Null candidate?? " + ThreadUtils.dumpStack());
            return null;
        }
        final Runnable threadRunner = new Runnable() {
            @Override
            public void run() {
                Socket sock = null;
                try {
                    log.info("Connecting to: {}", candidate);
                    //sock = new Socket();
                    sock = socketFactory.createSocket();
                    sock.setKeepAlive(true);
                    sock.connect(candidate.getSocketAddress(), 30 * 1000);

                    if (sock instanceof SSLSocket) {
                        // This is just for debugging.
                        final SSLSocket ssl = (SSLSocket) sock;
                        log.info("Enabled cipher suites on " +
                            "client side SSL socket: {}", 
                            Arrays.asList(ssl.getEnabledCipherSuites()));
                    }
                    
                    log.info("Client socket connected to: {}", 
                        sock.getRemoteSocketAddress());
                    onSocket(sock);
                    // Close this at the end in case it throws an exception.
                } catch (final IOException e) {
                    log.info("IO Exception connecting to: "+candidate, e);
                }
            }
        };
        /*
        final Thread connectorThread = new Thread(threadRunner,
                "ICE-TCP-Connect-For-Candidate-" + candidate + "-"+
                threadRunner.hashCode());
        connectorThread.setDaemon(true);
        connectorThread.start();
        */
        
        tcpIceServerThreadPool.execute(threadRunner);
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
        if (socketRef.compareAndSet(null, sock)) {
            log.info("Notifying listener of TCP socket: {}", 
                this.offerAnswerListener);
            this.offerAnswerListener.onTcpSocket(sock);
        }

        else {
            log.debug("Socket already exists! Ignoring second");
            // If a socket already existed, we close the socket *only if we're
            // the controlling peer*. Otherwise, it's possible for there to be 
            // a race condition where each side gets both possible successful 
            // sockets in rapid succession, causing both sides to close 
            // the second socket they receive, ultimately closing all 
            // successful sockets!!
            if (this.controlling) {
                log.debug("Closing on controlling candidate");
                try {
                    sock.close();
                } catch (final IOException e) {
                    log.error("Could not close socket", e);
                }
            } else {
                log.debug("Not closing on controlled candidate");

                // We also need to notify the listener a new socket has come in.
                // The controlling side will take care of closing it.
                this.offerAnswerListener.onTcpSocket(sock);
            }
        }
    }

    public Collection<? extends IceCandidate> gatherCandidates() {
        final Collection<IceCandidate> candidates = 
            new ArrayList<IceCandidate>(2);

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
            hostAddress, this.controlling);
        candidates.add(hostCandidate);

        final PublicIp ip = new PublicIpAddress();
        final InetAddress publicIp = ip.getPublicIpAddress();
        
        // OK, the following is non-standard. If we have a public address
        // for the host from our UDP STUN check, we use the address part for
        // a new candidate because we always make an effort to map our TCP
        // host port with UPnP. This is not a simultaneous open candidate,
        // although there may be cases where this actually succeeds when UPnP
        // mapping failed due to simultaneous open behavior on the NAT.
        if (publicIp != null && mappedServerSocket.isPortMapped()) {
            log.info("Adding public TCP address");
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
                publicIp, mappedServerSocket.getMappedPort());

            final IceCandidate publicHostCandidate = 
                new IceTcpHostPassiveCandidate(publicHostAddress, 
                    this.controlling);
            candidates.add(publicHostCandidate);
        } else {
            log.info("Not adding public candidate. PA: "+publicIp + 
                " mapped: " + mappedServerSocket.isPortMapped());
        }
        return candidates;
    }

    public InetSocketAddress getHostAddress() {
        return mappedServerSocket.getHostAddress();
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

    public void useRelay() {
        // The controlling code decided to use the relay. Nothing to do here.
    }
}
