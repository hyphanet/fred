package freenet.node;

import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.pluginmanager.PluginAddress;
import freenet.support.Logger;
import freenet.support.transport.ip.HostnameSyntaxException;

/**
 * Simple implementation of PluginAddress for the IP based TransportPlugin type.
 * @author chetan
 *
 */
public class PeerPluginAddress implements PluginAddress {

	public Peer peer;
	
	public PeerPluginAddress(InetAddress inetAddress, int portNumber) {
		this.peer = new Peer(inetAddress, portNumber);
	}
	
	public PeerPluginAddress(FreenetInetAddress freenetAddress, int portNumber) {
		this.peer = new Peer(freenetAddress, portNumber);
	}
	
	public PeerPluginAddress(Peer peer) {
		this.peer = peer;
	}
	
	public PeerPluginAddress(String physicalPeer, boolean fromLocal, boolean checkHostnameOrIPSyntax) throws UnknownHostException, HostnameSyntaxException, PeerParseException {
		this.peer = new Peer(physicalPeer, true, checkHostnameOrIPSyntax);
	}
	
	@Override
	public String toStringAddress() {
		return peer.toString();
	}
	
	public Peer getPeer(String physicalPeer, boolean fromLocal, boolean checkHostnameOrIPSyntax) {
		Peer p;
		try {
			p = new Peer(physicalPeer, true, checkHostnameOrIPSyntax);
		} catch(HostnameSyntaxException e) {
			if(fromLocal)
				Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physicalPeer);
			System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physicalPeer);
			return null;
		} catch (PeerParseException e) {
			if(fromLocal)
				Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physicalPeer);
			System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physicalPeer);
			return null;
		} catch (UnknownHostException e) {
			if(fromLocal)
				Logger.error(this, "Invalid hostname or IP Address syntax error while parsing peer reference in local peers list: " + physicalPeer);
			System.err.println("Invalid hostname or IP Address syntax error while parsing peer reference: " + physicalPeer);
			return null;
		}
		return p;
	}

	@Override
	public void updateHostname() throws UnsupportedOperationException {
		peer.getHandshakeAddress();
	}

	@Override
	public void dropHostName() throws UnsupportedOperationException {
		peer.dropHostName();
	}

	@Override
	public boolean laxEquals(Object o) {
		return peer.laxEquals(o);
	}

	@Override
	public boolean strictEquals(Object o) {
		return peer.strictEquals(o);
	}

	@Override
	public byte[] getBytes() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public PluginAddress getPhysicalAddress() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public FreenetInetAddress getFreenetAddress()
			throws UnsupportedOperationException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getPortNumber() throws UnsupportedOperationException {
		// TODO Auto-generated method stub
		return 0;
	}

}
