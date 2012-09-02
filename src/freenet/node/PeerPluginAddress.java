package freenet.node;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
		InetAddress addr = peer.getAddress(false);
		if(peer.getPort() == 0 && addr != null) {
			// Don't do a lookup again
			return addr.getAddress();
		}
		return byteAddress();
	}
	
	private byte[] byteAddress() {
		/*
		 * Flags-
		 * 0 - No IP address, no HostName
		 * 1 - IP address, no HostName
		 * 2 - No IP address, HostName
		 * 3 - IP address, HostName
		 */
		
		byte[] inetAddress = null;
		String hostName = null;
		int port = peer.getPort();
		
		FreenetInetAddress address = peer.getFreenetAddress();
		if(address != null) {
			if(address.getAddress(false) != null)
				inetAddress = address.getAddress(false).getAddress();
			hostName = address.getHostName();
		}
		
		ByteArrayOutputStream bAOS = new ByteArrayOutputStream();
		DataOutputStream dOS = new DataOutputStream(bAOS);
		try {	
			if(inetAddress == null && hostName == null) {
				dOS.writeByte(0);
				dOS.write(port);
			}
			else if(inetAddress != null && hostName == null) {
				dOS.writeByte(1);
				dOS.write(port);
				dOS.write(inetAddress);
			}
			else if(inetAddress == null && hostName != null) {
				dOS.writeByte(2);
				dOS.write(port);
				dOS.writeUTF(hostName);
			}
			else {
				dOS.writeByte(3);
				dOS.write(port);
				dOS.write(inetAddress);
				dOS.writeUTF(hostName);
			}
			dOS.flush();
		} catch (IOException e) {
			Logger.error(this, "Something happened: " + e.getStackTrace(), e);
		}
		return bAOS.toByteArray();
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
