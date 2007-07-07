package org.lastbamboo.common.ice;

import junit.framework.TestCase;

public class IceUdpStunClientTest extends TestCase
    {

    public void testClient() throws Exception
        {
        
        // This makes sure all the address are set, as the UDP client has
        // to resort to some crazy reflection with MINA to get the local 
        // address.
        final IceUdpStunClient stunClient = new IceUdpStunClient();
        assertNotNull(stunClient.getHostAddress());
        assertNotNull(stunClient.getServerReflexiveAddress());
        assertNotNull(stunClient.getBaseAddress());
        assertNotNull(stunClient.getStunServerAddress());
        }
    }