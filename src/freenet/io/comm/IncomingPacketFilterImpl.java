/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.comm;

import freenet.crypt.EntropySource;
import freenet.node.FNPPacketMangler;
import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

public class IncomingPacketFilterImpl implements IncomingPacketFilter {

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, IncomingPacketFilterImpl.class);
			}
		});
	}

	private FNPPacketMangler mangler;
	private Node node;
	private final EntropySource fnpTimingSource;

	public IncomingPacketFilterImpl(FNPPacketMangler mangler, Node node) {
		this.mangler = mangler;
		this.node = node;
		fnpTimingSource = new EntropySource();
	}

	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !context.isConnected();
	}

	public void process(byte[] buf, int offset, int length, Peer peer, long now) {
		if(logMINOR) Logger.minor(this, "Packet length "+length+" from "+peer);
		node.random.acceptTimerEntropy(fnpTimingSource, 0.25);
		PeerNode pn = node.peers.getByPeer(peer);

		if(pn != null) {
			pn.handleReceivedPacket(buf, offset, length, now, peer);
		} else {
			Logger.normal(this, "Got packet from unknown address");
			mangler.process(buf, offset, length, peer, now);
		}
	}

}
