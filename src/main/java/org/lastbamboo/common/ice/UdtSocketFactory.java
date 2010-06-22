package org.lastbamboo.common.ice;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.DatagramChannel;

import org.lastbamboo.common.offer.answer.OfferAnswerListener;
import org.lastbamboo.common.stun.server.StunServer;
import org.littleshoot.mina.common.IoAcceptor;
import org.littleshoot.mina.common.IoService;
import org.littleshoot.mina.common.IoSession;
import org.littleshoot.mina.transport.socket.nio.support.DatagramSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import udt.UDPEndPoint;
import udt.UDTClient;
import udt.UDTReceiver;
import udt.UDTServerSocket;
import udt.UDTSocket;

public class UdtSocketFactory implements UdpSocketFactory
    {
    
    private final Logger m_log = LoggerFactory.getLogger(getClass());

    public void newSocket(final IoSession session, final boolean controlling,
        final OfferAnswerListener socketListener, 
        final IceStunUdpPeer stunUdpPeer) 
        {
        if (session == null)
            {
            m_log.error("Null session: {}", session);
            return;
            }
        
        UDTReceiver.connectionExpiryDisabled=true;
        clear(session, stunUdpPeer);
        if (!controlling)
            {
            m_log.debug(
                "Creating UDT client socket on CONTROLLED agent.");
            final Runnable clientRunner = new Runnable()
                {
                public void run()
                    {
                    try
                        {
                        openAnswererSocket(session, socketListener);
                        }
                    catch (final Throwable t)
                        {
                        m_log.error("Client socket exception", t);
                        }
                    }
                };
    
            final Thread rudpClientThread = 
                new Thread(clientRunner, "RUDP Client Thread");
            rudpClientThread.setDaemon(true);
            rudpClientThread.start();
            }
        else
            {
            m_log.debug(
                "Creating UDT server socket on CONTROLLING agent.");
            m_log.debug("Listening on: {}", session);
            
            // If we call "accept" right away here, we'll kill the
            // IoSession thread and won't receive messages, so we 
            // need to start a new thread.
            final Runnable serverRunner = new Runnable ()
                {
                public void run()
                    {
                    try
                        {
                        openOffererSocket(session, socketListener);
                        }
                    catch (final Throwable t)
                        {
                        m_log.error("Server socket exception", t);
                        }
                    }
                };
            final Thread serverThread = 
                new Thread (serverRunner, "RUDP Accepting Thread");
            serverThread.setDaemon(true);
            serverThread.start();
            }
        }

    protected void openAnswererSocket(final IoSession session,
        final OfferAnswerListener socketListener) 
        throws InterruptedException, IOException 
        {
        final InetSocketAddress local = 
            (InetSocketAddress) session.getLocalAddress();
        final InetSocketAddress remote = 
            (InetSocketAddress) session.getRemoteAddress();

        m_log.info("Session local was: {}", local);
        m_log.info("Binding to port: {}", local.getPort());
        
        //final UDTClient client = new UDTClient(new UDPEndPoint(dgSock));
        //final UDTClient client = new UDTClient(local.getAddress(), local.getPort());
        //final UDTClient client = new UDTClient(new UDPEndPoint(dgChannel.socket()));
        final UDTClient client = new UDTClient(new UDPEndPoint(local.getPort()));
        
        client.connect(remote.getAddress(), remote.getPort());
        final Socket sock = client.getSocket();
        m_log.info("Got socket...notifying listener");
        socketListener.onUdpSocket(sock);
        m_log.info("Exiting...");
        }

    protected void openOffererSocket(final IoSession session,
        final OfferAnswerListener socketListener) 
        throws InterruptedException, IOException 
        {
        final InetSocketAddress local = 
            (InetSocketAddress) session.getLocalAddress();

        m_log.info("Session local was: {}", local);
        m_log.info("Binding to port: {}", local.getPort());
        final UDTServerSocket server = 
            new UDTServerSocket(local.getAddress(), local.getPort());
        //final UDTServerSocket server = 
        //    new UDTServerSocket(new UDPEndPoint(dgChannel.socket()));
        
        final UDTSocket sock = server.accept();
        socketListener.onUdpSocket(sock);
        }
    
    private void clear(final IoSession session, 
        final IceStunUdpPeer stunUdpPeer) 
        {
        m_log.info("Clearing session!!");
        final DatagramSessionImpl dgSession = (DatagramSessionImpl)session;
        final DatagramChannel dgChannel = dgSession.getChannel();
        final DatagramSocket dgSock = dgChannel.socket();
        m_log.info("Closing socket on local address: {}", 
            dgSock.getLocalSocketAddress());
        session.close().join(10 * 1000);
        
        final StunServer stunServer = stunUdpPeer.getStunServer();
        stunServer.close();
        try
            {
            final IoService service = session.getService();
            m_log.info("Service is: {}", service);
            if (IoAcceptor.class.isAssignableFrom(service.getClass()))
                {
                m_log.info("Unbinding all!!");
                final IoAcceptor acceptor = (IoAcceptor) service;
                acceptor.unbindAll();
                }
            session.getService().getFilterChain().clear();
            dgChannel.disconnect();
            dgChannel.close();
            Thread.sleep(2000);
            }
        catch (final Exception e)
            {
            m_log.error("Error clearing session!!", e);
            }
        }
    }