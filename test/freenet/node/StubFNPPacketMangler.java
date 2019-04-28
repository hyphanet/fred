/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package freenet.node;

import freenet.io.AddressTracker;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.SocketHandler;

/**
 *
 * @author martin
 */
public class StubFNPPacketMangler extends FNPPacketMangler {

    AddressTracker.Status mConnectivityStatus = AddressTracker.Status.DONT_KNOW;
    
    public StubFNPPacketMangler(ProtectedNode node, NodeCrypto crypt, PacketSocketHandler sock) {
        super(node, crypt, sock);
    }
    
    @Override
    public void sendHandshake(PeerNode pn, boolean notRegistered) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean isDisconnected(PeerContext context) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public int[] supportedNegTypes(boolean forPublic) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public SocketHandler getSocketHandler() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Peer[] getPrimaryIPAddress() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public byte[] getCompressedNoderef() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean alwaysAllowLocalAddresses() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public AddressTracker.Status getConnectivityStatus() {
        return mConnectivityStatus;
    }
    
    public void setConnectivityStatus(AddressTracker.Status status) {
        mConnectivityStatus = status;
    }

    @Override
    public boolean allowConnection(PeerNode node, FreenetInetAddress addr) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setPortForwardingBroken() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
    
}
