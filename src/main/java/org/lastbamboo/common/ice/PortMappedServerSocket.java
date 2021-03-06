package org.lastbamboo.common.ice;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.lastbamboo.common.portmapping.PortMapListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortMappedServerSocket implements PortMapListener,
    MappedServerSocket {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final ServerSocket serverSocket;
    private int externalPort;
    private boolean hasMappedPort;

    private final boolean isPublic;

    public PortMappedServerSocket(final ServerSocket serverSocket, 
        final boolean isPublic) {
        this.serverSocket = serverSocket;
        this.isPublic = isPublic;
        if (isPublic) {
            this.hasMappedPort = true;
        }
    }

    public void onPortMap(final int port) {
        log.info("Got port mapped!! "+port);
        this.externalPort = port;
        hasMappedPort = true;
    }

    public void onPortMapError() {
        log.info("Error mapping port...");
        if (!isPublic) {
            hasMappedPort = false;
        }
    }
    

    public ServerSocket getServerSocket() {
        return serverSocket;
    }
    

    public boolean isPortMapped() {
        return this.hasMappedPort;
    }

    public int getMappedPort() {
        return this.externalPort;
    }
    

    public InetSocketAddress getHostAddress() {
        return (InetSocketAddress) this.serverSocket.getLocalSocketAddress();
    }
    
    @Override
    public String toString() {
        return "PortMappedServerSocket [serverSocket=" + serverSocket
                + ", externalPort=" + externalPort + "]";
    }
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + externalPort;
        result = prime * result
                + ((serverSocket == null) ? 0 : serverSocket.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PortMappedServerSocket other = (PortMappedServerSocket) obj;
        if (externalPort != other.externalPort)
            return false;
        if (serverSocket == null) {
            if (other.serverSocket != null)
                return false;
        } else if (!serverSocket.equals(other.serverSocket))
            return false;
        return true;
    }
}
