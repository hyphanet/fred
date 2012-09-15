package freenet.node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.pluginmanager.MalformedPluginAddressException;
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
	
	/**
	 * Flag(one byte)-
	 * <br>0 - No IP address, no HostName
	 * <br>
	 * <br>1 - IP4 address, no HostName
	 * <br>2 - IP6 address, no HostName
	 * <br>
	 * <br>3 - IP4 address, HostName
	 * <br>4 - IP6 address, HostName
	 * <br>
	 * <br>5 - No IP address, HostName
	 * <br>
	 * <br>IP4 address = 4 bytes
	 * <br>IP6 address = 16 bytes
	 *
	 * @param address
	 * @throws IOException 
	 * @throws MalformedPluginAddressException 
	 */
	public PeerPluginAddress(byte[] peerAddress) throws IOException, MalformedPluginAddressException {
		
		ByteArrayInputStream bAIS = new ByteArrayInputStream(peerAddress);
		DataInputStream dIS = new DataInputStream(bAIS);
		int flag = dIS.readByte();
		int port = dIS.read();
		if(flag == 0) {
			// Oops! What do we do now?
			// We could use loopback/127.0.0.1
			if(port == 0)
				throw new MalformedPluginAddressException("No InetAddress or HostName and port = 0");
			byte[] loopback = { 127, 0, 0, 1 };
			this.peer = new Peer(InetAddress.getByAddress(loopback), port);
		} else if(flag == 1 || flag == 2) {
			// 4 or 16 bytes for an InetAddress
			int size = (flag == 1) ? 4 : 16;
			byte[] addr = new byte[size];
			for(int i = 0; i < size; i++)
				addr[i] = (byte) dIS.read();
			this.peer = new Peer(InetAddress.getByAddress(addr), port);
		} else if(flag == 3 || flag == 4) {
			// 4 or 16 bytes for an InetAddress
			int size = (flag == 3) ? 4 : 16;
			byte[] addr = new byte[size];
			for(int i = 0; i < size; i++)
				addr[i] = (byte) dIS.read();
			String hostName = dIS.readUTF();
			InetAddress inet = InetAddress.getByAddress(addr);
			this.peer = new Peer(new FreenetInetAddress(inet, hostName), port);
		} else if(flag == 5) {
			String hostName = dIS.readUTF();
			this.peer = new Peer(new FreenetInetAddress(hostName, true), port);
		} else
			throw new MalformedPluginAddressException("Unknown flag");
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
	
	/**
	 * Flag(one byte)-
	 * <br>0 - No IP address, no HostName
	 * <br>
	 * <br>1 - IP4 address, no HostName
	 * <br>2 - IP6 address, no HostName
	 * <br>
	 * <br>3 - IP4 address, HostName
	 * <br>4 - IP6 address, HostName
	 * <br>
	 * <br>5 - No IP address, HostName
	 * <br>
	 * <br>IP4 address = 4 bytes
	 * <br>IP6 address = 16 bytes
	 */
	private byte[] byteAddress() {
		
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
			} else if(inetAddress != null && hostName == null) {
				if(inetAddress.length == 4)
					dOS.writeByte(1);
				else if(inetAddress.length == 16)
					dOS.writeByte(2);
				dOS.write(port);
				dOS.write(inetAddress);
			} else if(inetAddress != null && hostName != null){
				if(inetAddress.length == 4)
					dOS.writeByte(3);
				else if(inetAddress.length == 16)
					dOS.writeByte(4);
				dOS.write(port);
				dOS.write(inetAddress);
				dOS.writeUTF(hostName);
			} else {
				dOS.writeByte(5);
				dOS.write(port);
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
		FreenetInetAddress addr = peer.getFreenetAddress();
		if(addr == null)
			return null;
		return new PeerPluginAddress(addr.getAddress(), 0);
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
