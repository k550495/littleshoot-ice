package org.lastbamboo.common.ice;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.IoHandler;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.DatagramAcceptor;
import org.apache.mina.transport.socket.nio.DatagramAcceptorConfig;
import org.apache.mina.transport.socket.nio.DatagramConnector;
import org.apache.mina.transport.socket.nio.DatagramConnectorConfig;
import org.lastbamboo.common.ice.stubs.IceAgentStub;
import org.lastbamboo.common.ice.stubs.IceMediaStreamImplStub;
import org.lastbamboo.common.stun.stack.StunIoHandler;
import org.lastbamboo.common.stun.stack.decoder.StunProtocolCodecFactory;
import org.lastbamboo.common.stun.stack.message.BindingErrorResponse;
import org.lastbamboo.common.stun.stack.message.BindingRequest;
import org.lastbamboo.common.stun.stack.message.BindingSuccessResponse;
import org.lastbamboo.common.stun.stack.message.StunMessage;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitor;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitorAdapter;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitorFactory;
import org.lastbamboo.common.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test sending of STUN messages between ICE STUN UDP peers.
 */
public class IceStunUdpPeerTest extends TestCase
    {

    private final Logger m_log = LoggerFactory.getLogger(getClass());
    
    public void testIceStunUdpPeers() throws Exception
        {
        final IceAgent agent = new IceAgentStub();
        final IceMediaStream mediaStream = new IceMediaStreamImplStub();
        final IceStunUdpPeer peer1 = new IceStunUdpPeer(agent, mediaStream);
        final IceStunUdpPeer peer2 = new IceStunUdpPeer(agent, mediaStream);
        
        final InetSocketAddress address1 = peer1.getHostAddress();
        final InetSocketAddress address2 = peer2.getHostAddress();
        
        assertFalse(address1.equals(address2));
        
        m_log.debug("Sending STUN request to: "+address2);
        
        final StunMessageVisitor<InetSocketAddress> visitor = 
            new StunMessageVisitorAdapter<InetSocketAddress>()
            {
            
            @Override
            public InetSocketAddress visitBindingSuccessResponse(
                final BindingSuccessResponse response)
                {
                return response.getMappedAddress();
                }

            public InetSocketAddress visitBindingErrorResponse(
                final BindingErrorResponse response)
                {
                return null;
                }
            };

        for (int i = 0; i < 10; i++)
            {
            final StunMessage msg1 = peer1.write(new BindingRequest(), address2);
            final InetSocketAddress mappedAddress1 = msg1.accept(visitor);
            assertEquals("Mapped address should equal the local address", 
                address1, mappedAddress1);
            
            final StunMessage msg2 = peer2.write(new BindingRequest(), address1);
            final InetSocketAddress mappedAddress2 = msg2.accept(visitor);
            assertEquals("Mapped address should equal the local address", 
                address2, mappedAddress2);
            }
        }
    
    /**
     * This test really just tests Java UDP handling and MINA UDP handling
     * with respect to SO_REUSEADDRESS.  Basically, we bind a server to a local
     * port and bind a bunch of UDP "clients" to the same port.  Those clients
     * are "connected" to remote hosts just using the connect() method of 
     * {@link DatagramChannel} under the covers of MINA.  We make sure that
     * any messages coming from those remote hosts are sent to the connected
     * clients and not to the server.  We then check to make sure messages
     * from random external clients go to the server -- the 
     * {@link DatagramChannel} that's just bound but hasn't had connect() 
     * called on it.
     *  
     * @throws Exception If any unexpected error occurs.
     */
    public void testUdpConnectingAndBinding() throws Exception
        {
        final AtomicInteger serverRequestsReceived = new AtomicInteger(0);
        final int expectedServerMessages = 60;
        final StunMessageVisitorFactory serverVisitorFactory =
            new StunMessageVisitorFactory<StunMessage>()
            {

            public StunMessageVisitor<StunMessage> createVisitor(
                final IoSession session)
                {
                final StunMessageVisitor<StunMessage> clientVisitor = 
                    new StunMessageVisitorAdapter<StunMessage>()
                    {
                    public StunMessage visitBindingRequest(
                        final BindingRequest request)
                        {
                        serverRequestsReceived.incrementAndGet();
                        if (serverRequestsReceived.get() == expectedServerMessages)
                            {
                            synchronized (serverRequestsReceived)
                                {
                                serverRequestsReceived.notify();
                                }
                            }
                        return null;
                        }
                    };
                return clientVisitor;
                }
            };
        
        final AtomicInteger clientRequestsReceived = new AtomicInteger(0);
        final int expectedClientMessages = 300;
        final StunMessageVisitorFactory clientVisitorFactory =
            new StunMessageVisitorFactory<StunMessage>()
            {
            public StunMessageVisitor<StunMessage> createVisitor(IoSession session)
                {
                final StunMessageVisitor<StunMessage> clientVisitor = 
                    new StunMessageVisitorAdapter<StunMessage>()
                    {
                    public StunMessage visitBindingRequest(final BindingRequest request)
                        {
                        clientRequestsReceived.incrementAndGet();
                        if (clientRequestsReceived.get() == expectedClientMessages)
                            {
                            synchronized (clientRequestsReceived)
                                {
                                clientRequestsReceived.notify();
                                }
                            }
                        return null;
                        }
                    };
                return clientVisitor;
                }
            };

        final InetSocketAddress boundAddress = 
            createServer(serverVisitorFactory);
        
        final Collection<InetSocketAddress> remoteAddresses = 
            createRemoteAddresses(42548);
        
        final IoHandler handler = new StunIoHandler(clientVisitorFactory);
        
        for (final InetSocketAddress remoteAddress : remoteAddresses)
            {
            // This binds the sessions and "connects" them to the remote host.
            createClientSession(boundAddress, remoteAddress, handler);
            }
        
        // Now create sessions from the remote host to the localhost and
        // check to see the "connected" UDP client receives the traffic, not
        // the acceptor/server.  Note the handler is irrelevant here.
        final Collection<IoSession> remoteSessions = 
            createRemoteSessions(remoteAddresses, boundAddress, handler);

        for (final IoSession remoteSession : remoteSessions)
            {
            for (int i = 0; i < 10; i++)
                {
                remoteSession.write(new BindingRequest());
                }
            }

        // Make sure the clients got all the messages.
        synchronized (clientRequestsReceived)
            {
            if (clientRequestsReceived.get() < expectedClientMessages)
                {
                clientRequestsReceived.wait(3000);
                }
            }
        
        assertEquals("Client did not receive expected messages", 
            clientRequestsReceived.get(), expectedClientMessages);
        
        // Now make sure that out of all the messages we just sent, none went
        // to the acceptor.
        assertTrue("Server received the message!!", 
            serverRequestsReceived.get() == 0);
        

        // Now make sure sending from a bunch of remote hosts from other ports
        // reaches the acceptor, not the clients.
        final Collection<InetSocketAddress> randomRemoteAddresses = 
            createRemoteAddresses(6321);
        final Collection<IoSession> randomRemoteSessions = 
            createRemoteSessions(randomRemoteAddresses, boundAddress, handler);
        
        for (final IoSession remoteSession : randomRemoteSessions)
            {
            for (int i = 0; i < 2; i++)
                {
                remoteSession.write(new BindingRequest());
                }
            }
        
        synchronized (serverRequestsReceived)
            {
            if (serverRequestsReceived.get() < expectedServerMessages)
                {
                serverRequestsReceived.wait(8000);
                }
            }
        // Now make sure the server **DID** receive the message.
        assertEquals("ERROR: server DID NOT receive the expected messages!!", 
            expectedServerMessages, serverRequestsReceived.get());
        
        // Make sure the number of client messages hasn't changed.
        assertEquals("Client did not receive expected messages", 
            clientRequestsReceived.get(), expectedClientMessages);
        }
    
    
    private Collection<IoSession> createRemoteSessions(
        final Collection<InetSocketAddress> remoteAddresses, 
        final InetSocketAddress boundAddress, 
        final IoHandler handler) throws Exception
        {
        final Collection<IoSession> remoteSessions = 
            new LinkedList<IoSession>();
        for (final InetSocketAddress remoteAddress : remoteAddresses)
            {
            final IoSession remoteSession = 
                createClientSession(remoteAddress, boundAddress, handler);
            remoteSessions.add(remoteSession);
            }
        return remoteSessions;
        }


    private Collection<InetSocketAddress> createRemoteAddresses(
        final int startPort) throws Exception
        {
        final LinkedList<InetSocketAddress> addresses = 
            new LinkedList<InetSocketAddress>();
        for (int i = 0; i < 30; i++)
            {
            final InetSocketAddress remote = 
                new InetSocketAddress(NetworkUtils.getLocalHost(), startPort+i);
            addresses.add(remote);
            }

        return addresses;
        }


    private InetSocketAddress createServer(
        final StunMessageVisitorFactory serverVisitorFactory) throws Exception
        {
        final int port = 47382;
        final InetSocketAddress boundAddress = 
            new InetSocketAddress(NetworkUtils.getLocalHost(), port);
        final DatagramAcceptor acceptor = new DatagramAcceptor();
        final DatagramAcceptorConfig config = new DatagramAcceptorConfig();
        config.getSessionConfig().setReuseAddress(true);
        
        final ProtocolCodecFactory codecFactory = 
            new StunProtocolCodecFactory();
        final ProtocolCodecFilter codecFilter = 
            new ProtocolCodecFilter(codecFactory);
        config.getFilterChain().addLast("stunFilter", codecFilter);
        final IoHandler handler = new StunIoHandler(serverVisitorFactory);
        
        acceptor.bind(boundAddress, handler, config);
        return boundAddress;
        }
    
    private IoSession createClientSession(final InetSocketAddress localAddress, 
        final InetSocketAddress remoteAddress, 
        final IoHandler ioHandler) throws Exception
        {
        final DatagramConnector connector = new DatagramConnector();
        final DatagramConnectorConfig cfg = connector.getDefaultConfig();
        cfg.getSessionConfig().setReuseAddress(true);
        final ProtocolCodecFactory codecFactory = 
            new StunProtocolCodecFactory();
        final ProtocolCodecFilter stunFilter = 
            new ProtocolCodecFilter(codecFactory);
        
        connector.getFilterChain().addLast("stunFilter", stunFilter);
        final ConnectFuture cf = 
            connector.connect(remoteAddress, localAddress, ioHandler);
        cf.join();
        final IoSession session = cf.getSession();
        return session;
        }
    }