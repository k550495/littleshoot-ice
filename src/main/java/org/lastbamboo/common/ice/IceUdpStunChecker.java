package org.lastbamboo.common.ice;

import java.net.InetSocketAddress;

import org.apache.commons.id.uuid.UUID;
import org.apache.mina.common.ConnectFuture;
import org.apache.mina.common.IoHandler;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.DatagramConnector;
import org.apache.mina.transport.socket.nio.DatagramConnectorConfig;
import org.lastbamboo.common.stun.stack.StunIoHandler;
import org.lastbamboo.common.stun.stack.decoder.StunProtocolCodecFactory;
import org.lastbamboo.common.stun.stack.message.BindingErrorResponse;
import org.lastbamboo.common.stun.stack.message.BindingRequest;
import org.lastbamboo.common.stun.stack.message.BindingSuccessResponse;
import org.lastbamboo.common.stun.stack.message.CanceledStunMessage;
import org.lastbamboo.common.stun.stack.message.NullStunMessage;
import org.lastbamboo.common.stun.stack.message.StunMessage;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitor;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitorAdapter;
import org.lastbamboo.common.stun.stack.message.StunMessageVisitorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that performs STUN connectivity checks for ICE over UDP.  Each 
 * ICE candidate pair has its own connectivity checker. 
 */
public class IceUdpStunChecker implements IceStunChecker
    {

    private final Logger m_log = LoggerFactory.getLogger(getClass());
    
    private final IoSession m_ioSession;

    private volatile StunMessage m_response;

    private volatile BindingRequest m_currentRequest;

    private volatile boolean m_transactionCancelled = false;

    /**
     * Creates a new ICE connectivity checker over UDP.
     * 
     * @param localAddress The local address.
     * @param remoteAddress The remote address.
     */
    public IceUdpStunChecker(final InetSocketAddress localAddress, 
        final InetSocketAddress remoteAddress)
        {
        this.m_ioSession = createClientSession(localAddress, remoteAddress);
        }
    
    private IoSession createClientSession(final InetSocketAddress localAddress, 
        final InetSocketAddress remoteAddress) 
        {
        final DatagramConnector connector = new DatagramConnector();
        final DatagramConnectorConfig cfg = connector.getDefaultConfig();
        cfg.getSessionConfig().setReuseAddress(true);
        final ProtocolCodecFactory codecFactory = 
            new StunProtocolCodecFactory();
        final ProtocolCodecFilter stunFilter = 
            new ProtocolCodecFilter(codecFactory);
        
        connector.getFilterChain().addLast("stunFilter", stunFilter);
        
        final StunMessageVisitorFactory<StunMessage> visitorFactory =
            new IceConnectivityStunMessageVisitorFactory();
        final IoHandler ioHandler = 
            new StunIoHandler<StunMessage>(visitorFactory);
        m_log.debug("Connecting from "+localAddress+" to "+remoteAddress);
        final ConnectFuture cf = 
            connector.connect(remoteAddress, localAddress, ioHandler);
        cf.join();
        final IoSession session = cf.getSession();
        return session;
        }
    
    private class IceConnectivityStunMessageVisitorFactory 
        implements StunMessageVisitorFactory<StunMessage>
        {

        public StunMessageVisitor<StunMessage> createVisitor(
            final IoSession session)
            {
            return new IceConnectivityStunMessageVisitor();
            }
        
        }
    
    private class IceConnectivityStunMessageVisitor 
        extends StunMessageVisitorAdapter<StunMessage>
        {

        public StunMessage visitBindingRequest(final BindingRequest request)
            {
            // TODO: We need to send the response, presumably with 
            // re-transmissions??
            
            m_log.debug("Got Binding Request!!!");
            return null;
            }
        
        public StunMessage visitBindingSuccessResponse(
            final BindingSuccessResponse response)
            {
            m_log.debug("Got Binding Response: {}", response);
            return handleResponse(m_currentRequest, response);
            }
        
        public StunMessage visitBindingErrorResponse(
            final BindingErrorResponse response)
            {
            m_log.debug("Got Binding Error Response: {}", response);
            return handleResponse(m_currentRequest, response);
            }   
        
        private StunMessage handleResponse(final BindingRequest request, 
            final StunMessage response)
            {
            final UUID requestId = request.getTransactionId();
            final UUID responseId = response.getTransactionId();
            if (requestId.equals(responseId))
                {
                // This indicates the transaction succeeded, although it may
                // have resulted in a Binding Error Response.
                synchronized (request)
                    {
                    m_response = response;
                    request.notify();
                    }
                return response;
                }
            else
                {
                m_log.warn("Response has different transaction ID");
                return new NullStunMessage();
                }

            }
        }

    public StunMessage write(final BindingRequest bindingRequest, 
        final long rto)
        {
        
        // TODO: We need to be able to cancel this request and not hold the 
        // lock forever!!
        this.m_transactionCancelled = false;
        
        // This method will retransmit the same request multiple times 
        // because it's being sent unreliably.  All of the requests will be 
        // identical, using the same transaction ID.
        this.m_currentRequest = bindingRequest;
        
        synchronized (this.m_currentRequest)
            {   
            m_log.debug("Got request lock");
            int requests = 0;
            
            long waitTime = 0L;

            while (!transactionComplete() && 
                requests < 7 && 
                !this.m_transactionCancelled)
                {
                m_log.debug("Waiting...");
                waitIfNoResponse(bindingRequest, waitTime);
                
                // See draft-ietf-behave-rfc3489bis-06.txt section 7.1.  We
                // continually send the same request until we receive a 
                // response, never sending more that 7 requests and using
                // an expanding interval between requests based on the 
                // estimated round-trip-time to the server.  This is because
                // some requests can be lost with UDP.
                m_log.debug("Writing Binding Request...");
                this.m_ioSession.write(bindingRequest);
                
                // Wait a little longer with each send.
                waitTime = (2 * waitTime) + rto;
                
                requests++;
                }
            
            // Now we wait for 1.6 seconds after the last request was sent.
            // If we still don't receive a response, then the transaction 
            // has failed.  
            waitIfNoResponse(bindingRequest, 1600);
            if (transactionComplete())
                {
                m_log.debug("Returning success response...");
                return this.m_response;
                }
            
            if (this.m_transactionCancelled)
                {
                m_log.debug("The transaction was canceled!");
                return new CanceledStunMessage();
                }
            else
                {
                m_log.warn("Did not get response!!");
                return new NullStunMessage();
                }
            }
        }
    
    public void cancelTransaction()
        {
        m_log.debug("Cancelling transaction!!");
        this.m_transactionCancelled = true;
        }
    

    private boolean transactionComplete()
        {
        return this.m_response != null;
        }

    private void waitIfNoResponse(final BindingRequest request, 
        final long waitTime)
        {
        if (this.m_response == null && waitTime > 0L)
            {
            try
                {
                request.wait(waitTime);
                }
            catch (final InterruptedException e)
                {
                m_log.error("Unexpected interrupt", e);
                }
            }
        }

    }