/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.node.FNPPacketMangler;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Logger;

public class IncomingPacketFilterImpl implements IncomingPacketFilter {

	private FNPPacketMangler mangler;
	private Node node;

	public IncomingPacketFilterImpl(FNPPacketMangler mangler, Node node) {
		this.mangler = mangler;
		this.node = node;
	}

	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !context.isConnected();
	}

	public void process(byte[] buf, int offset, int length, Peer peer, long now) {
		PeerNode pn = node.peers.getByPeer(peer);

		if(pn != null) {
			pn.handleReceivedPacket(buf, offset, length, now);
		} else {
			Logger.normal(this, "Got packet from unknown address");
			mangler.process(buf, offset, length, peer, now);
		}
	}

}
