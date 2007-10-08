package org.lastbamboo.common.ice.candidate;

import java.net.Socket;

import org.apache.mina.common.IoSession;
import org.lastbamboo.common.ice.IceStunCheckerFactory;
import org.lastbamboo.common.ice.transport.IceTcpConnector;
import org.lastbamboo.common.tcp.frame.TcpFrameIoHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TCP ICE candidate pair. 
 */
public class TcpIceCandidatePair extends AbstractIceCandidatePair
    {

    private final Logger m_log = LoggerFactory.getLogger(getClass());
    
    private final int m_pairId;
    
    private static int s_pairId = 0;

    private final TcpFrameIoHandler m_frameIoHandler;
    
    /**
     * Pair of TCP ICE candidates.
     * 
     * @param localCandidate The local candidate.
     * @param remoteCandidate The remote candidate.
     * @param ioSession The connection between the two endpoints.
     */
    public TcpIceCandidatePair(final IceCandidate localCandidate, 
        final IceCandidate remoteCandidate, final IoSession ioSession, 
        final IceStunCheckerFactory stunCheckerFactory, 
        final TcpFrameIoHandler frameIoHandler)
        {
        super(localCandidate, remoteCandidate, ioSession, stunCheckerFactory);
        if (frameIoHandler == null)
            {
            throw new NullPointerException("Null frame io handler");
            }
        if (ioSession == null)
            {
            throw new NullPointerException("Null session");
            }
        m_frameIoHandler = frameIoHandler;
        this.m_pairId = s_pairId;
        s_pairId++;
        }
    
    /**
     * Pair of TCP ICE candidates.
     * 
     * @param localCandidate The local candidate.
     * @param remoteCandidate The remote candidate.
     */
    public TcpIceCandidatePair(final IceCandidate localCandidate, 
        final IceCandidate remoteCandidate,
        final IceStunCheckerFactory stunCheckerFactory,
        final IceTcpConnector iceConnector)
        {
        super(localCandidate, remoteCandidate, stunCheckerFactory, iceConnector);
        this.m_frameIoHandler = iceConnector.getStreamIoHandler();
        if (m_frameIoHandler == null)
            {
            throw new NullPointerException("Null frame io handler");
            }
        this.m_pairId = s_pairId;
        s_pairId++;
        }


    public TcpFrameIoHandler getTcpFrameIoHandler()
        {
        return this.m_frameIoHandler;
        }
    
    public void setIoSession(final IoSession session)
        {
        if (this.m_ioSession != null)
            {
            m_log.warn("Ignoring set session because it already exists!!");
            return;
            }
        this.m_ioSession = session;
        }
    
    public Socket getSocket()
        {
        return this.m_frameIoHandler.getSocket();
        }

    public <T> T accept(final IceCandidatePairVisitor<T> visitor)
        {
        return visitor.visitTcpIceCandidatePair(this);
        }
    
    @Override
    public String toString()
        {
        return getClass().getSimpleName() + " " + this.m_pairId;
        }

    }
