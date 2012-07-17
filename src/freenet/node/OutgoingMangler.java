package freenet.node;

import freenet.io.AddressTracker.Status;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.PeerContext;
import freenet.pluginmanager.TransportPlugin;

public interface OutgoingMangler {
	
	/**
	 * Send a handshake, if possible, to the node.
	 * @param pn
	 */
	public void sendHandshake(PeerNode pn, boolean notRegistered);
	
	/**
	 * Is a peer disconnected?
	 */
	public boolean isDisconnected(PeerContext context);
	
	public TransportPlugin getTransport();
	
	/**
	 * Port forwarding status.
	 * @return A status code from AddressTracker. FIXME make this more generic when we need to.
	 */
	public Status getConnectivityStatus();
	
	/**
	 * Is there any reason not to allow this connection? E.g. limits on the number of nodes on
	 * a specific IP address?
	 */
	public boolean allowConnection(PeerNode node, FreenetInetAddress addr);
	
	/**
	 * If the lower level code detects the port forwarding is broken, it will call this method.
	 */
	public void setPortForwardingBroken();
	
	/**
	 * Get our compressed noderef
	 */
	public byte[] getCompressedNoderef();
	
	/**
	 * Always allow local addresses?
	 */
	public boolean alwaysAllowLocalAddresses();

}
