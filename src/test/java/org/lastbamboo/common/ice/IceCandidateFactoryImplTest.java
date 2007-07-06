package org.lastbamboo.common.ice;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.Iterator;

import junit.framework.TestCase;

import org.lastbamboo.common.ice.candidate.IceCandidate;
import org.lastbamboo.common.ice.candidate.IceTcpHostPassiveCandidate;
import org.lastbamboo.common.ice.candidate.IceTcpRelayPassiveCandidate;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoder;
import org.lastbamboo.common.ice.sdp.IceCandidateSdpDecoderImpl;
import org.lastbamboo.common.sdp.api.SessionDescription;

/**
 * Test for the class for creating ICE candidates.
 */
public class IceCandidateFactoryImplTest extends TestCase 
    {

    /**
     * Tests the method for creating the candidates.
     * @throws Exception If there's any unexpected error.
     */
    public void testCreateCandidates() throws Exception
        {
        final IceCandidateSdpDecoder decoder = new IceCandidateSdpDecoderImpl();
        
        final String tcpLocalHostString = "192.168.1.6";
        final String tcpHostString = "72.3.139.235";
        final String udpHostString = "69.203.29.241";
        final int tcpLocalPort = 8107;
        final int tcpPort = 54684;
        final int udpPort = 8107;
        final String candidateString = 
            "v=0\r\n" +
            "o=- 0 0 IN IP4 192.168.2.34\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "m=message 8107 udp http\r\n" +
            "c=IN IP4 " + udpHostString + "\r\n" +
            "a=candidate:1 1 UDP 2130706431 "+udpHostString+" "+ udpPort+" typ host\r\n" + 
            //"a=candidate:1 657a41b3-f487-46a6-aaca-88693b357a49 udp 1 " + udpHostString + " " + udpPort+"\r\n" +
            
            // TURN address
            "m=message 54684 tcp http\r\n" +
            "c=IN IP4 " + tcpHostString + "\r\n" +
            "a=candidate:1 1 tcp-pass 2130706431 "+tcpHostString+" "+ tcpPort+" typ relay raddr 10.0.1.1 rport 8998\r\n" + 
            //"a=candidate:1 3a2035f4-0d39-4633-8c11-452a0117682a tcp-pass 2 " + tcpHostString + " " + tcpPort+"\r\n" +
            "a=setup:passive\r\n" +
            "a=connection:new\r\n" +
            
            // local address
            "m=message 8107 tcp http\r\n"+
            "c=IN IP4 "+ tcpLocalHostString + "\r\n"+
            "a=candidate:1 1 tcp-pass 2130706431 "+tcpLocalHostString+" "+ tcpLocalPort+" typ host\r\n" + 
            //"a=candidate:1 96013295-480f-4e9e-8706-d2316b6881da tcp-pass 1 "+tcpLocalHostString+" " + tcpLocalPort + "\r\n"+
            "a=setup:passive\r\n"+
            "a=connection:new";
        
        final org.lastbamboo.common.sdp.api.SdpFactory sdpFactory = 
            org.lastbamboo.common.sdp.api.SdpFactory.getInstance();
        final SessionDescription sdp = 
            sdpFactory.createSessionDescription(candidateString);

        final TestIceCandidateVisitor visitor = new TestIceCandidateVisitor();
        final Collection candidates = decoder.decode(sdp);
        assertEquals("Unexpected number of candidates", 3, candidates.size());
        
        visitor.visitCandidates(candidates);
        
        final Collection tcpCandidates = visitor.m_tcpPassiveRemoteCandidates;
        final Collection udpCandidates = visitor.m_udpCandidates;
        
        assertNotNull(tcpCandidates);
        assertNotNull(udpCandidates);
        
        assertFalse(tcpCandidates.isEmpty());
        assertFalse(udpCandidates.isEmpty());
        
        final Iterator tcpIter = tcpCandidates.iterator();

        // Make sure it ranks them appropriately -- the should be in the order
        // we assume here.
        final IceTcpHostPassiveCandidate tcpLocalCandidate = 
            (IceTcpHostPassiveCandidate) tcpIter.next();
        final IceTcpRelayPassiveCandidate tcpCandidate = 
            (IceTcpRelayPassiveCandidate) tcpIter.next();
        
        final Iterator udpIter = udpCandidates.iterator();
        final IceCandidate udpCandidate = (IceCandidate) udpIter.next();
        
        assertTrue("Got "+tcpLocalCandidate.getClass(), 
            (tcpLocalCandidate instanceof IceTcpHostPassiveCandidate));
        assertTrue("Got "+tcpCandidate.getClass(), 
            (tcpCandidate instanceof IceTcpRelayPassiveCandidate));
        assertEquals("tcp-pass", tcpLocalCandidate.getTransport().getName());
        assertEquals("tcp-pass", tcpCandidate.getTransport().getName());
        assertEquals("udp", udpCandidate.getTransport().getName());
        
        final InetSocketAddress tcpLocalSocketAddress = 
            new InetSocketAddress(tcpLocalHostString, tcpLocalPort);
        final InetSocketAddress tcpSocketAddress = 
            new InetSocketAddress(tcpHostString, tcpPort);
        final InetSocketAddress udpSocketAddress = 
            new InetSocketAddress(udpHostString, udpPort);
        
        assertEquals(tcpLocalSocketAddress, tcpLocalCandidate.getSocketAddress());
        assertEquals(tcpSocketAddress, tcpCandidate.getSocketAddress());
        assertEquals(udpSocketAddress, udpCandidate.getSocketAddress());
        }
    
    private final class TestIceCandidateVisitor 
        extends AbstractIceCandidateTracker
        {

        public void visitUnknownIceCandidate(final IceCandidate candidate)
            {
            throw new IllegalArgumentException("Unknown candidate: "+candidate);
            }

        public Socket getBestSocket() throws IceException
            {
            // TODO Auto-generated method stub
            return null;
            }
    
        }

    }
