package freenet.node;

import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.pluginmanager.PluginAddress;
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
	
	@Override
	public void updateHostName() {
		peer.getHandshakeAddress();
	}

	@Override
	public PluginAddress dropHostName() {
		return new PeerPluginAddress(peer.dropHostName());
		
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
		return peer.toString().getBytes();
	}

	@Override
	public PluginAddress getPhysicalAddress() {
		try {
			return new PeerPluginAddress(peer.getFreenetAddress().getAddress(), 0);
		} catch(NullPointerException e) {
			return null;
		}
	}

	@Override
	public FreenetInetAddress getFreenetAddress() {
		return peer.getFreenetAddress();
	}

	@Override
	public int getPortNumber() {
		return peer.getPort();
	}

}
