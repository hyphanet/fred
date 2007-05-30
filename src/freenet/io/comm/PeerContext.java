/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.io.xfer.PacketThrottle;

/**
 * @author amphibian
 * 
 * Everything that is needed to send a message, including the Peer.
 * Implemented by PeerNode, for example.
 */
public interface PeerContext {
    // Largely opaque interface for now
    Peer getPeer();

    /** Force the peer to disconnect */
	void forceDisconnect();

	/** Is the peer connected? Have we established the session link? */
	boolean isConnected();
	
	/** Is the peer connected? are we able to route requests to it? */
	boolean isRoutable();

	/** Peer version, if this is supported, else -1 */
	int getVersionNumber();
	
	/** Send a message to the node */
	public void sendAsync(Message msg, AsyncMessageCallback cb, int alreadyReportedBytes, ByteCounter ctr) throws NotConnectedException;
	
	/** Get the current boot ID. This is a random number that changes every time the node starts up. */
	public long getBootID();

	/** Get the PacketThrottle for the node's current address for the standard packet size (if the 
	 * address changes then we get a new throttle). */ 
	public PacketThrottle getThrottle();
}
