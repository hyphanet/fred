/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

/**
 * @author amphibian
 * 
 * Default PeerContext if we don't have a IncomingPacketFilter installed.
 * Just carries the Peer.
 */
public class DummyPeerContext implements PeerContext {

    private final Peer peer;
    private final UdpSocketManager usm;
    
    public Peer getPeer() {
        return peer;
    }
    
    DummyPeerContext(Peer p, UdpSocketManager usm) {
        peer = p;
        this.usm = usm;
    }

	public void forceDisconnect() {
		// Do nothing
	}

	public boolean isRoutable() {
		return false;
	}
	
	public boolean isConnected() {
		return false;
	}

	public void reportOutgoingBytes(int length) {
		// Ignore
	}

	public int getVersionNumber() {
		return -1;
	}

	public void sendAsync(Message msg, AsyncMessageCallback cb, int alreadyReportedBytes, ByteCounter ctr) throws NotConnectedException {
		usm.send(this, msg, ctr);
		try {
			cb.sent();
		} catch (Throwable t) {
			// FIXME do something with it
		}
	}
}
