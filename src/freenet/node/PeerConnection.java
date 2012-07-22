package freenet.node;

import java.security.MessageDigest;
import java.util.Vector;

import freenet.crypt.SHA256;
import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.TransportPlugin;
import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
/**
 * Base object of PeerPacketConnection and PeerStreamConnection. Fields common to both are present.
 * @author chetan
 *
 */
public class PeerConnection {
	
	protected final String transportName;
	
	protected final TransportPlugin transportPlugin;
	
	/** Every connection can belong to only one peernode. */
	protected final PeerNode pn;
	
	/** The peer it connects to */
	protected PluginAddress detectedTransportAddress;
	
	/** How much data did we send with the current tracker ? */
	public long totalBytesExchangedWithCurrentTracker = 0;
	
	/** List of keys for every connection. Multiple setups might complete simultaneously.
	 * This will also be used to replace current, previous and unverified to make it more generic.
	 */
	public Vector<SessionKey> keys = new Vector<SessionKey> ();
	
	public SessionKey currentTracker = null;
	public SessionKey previousTracker = null;
	public SessionKey unverifiedTracker = null;
	
	static final byte[] TEST_AS_BYTES = PeerNode.TEST_AS_BYTES;
	static final int CHECK_FOR_SWAPPED_TRACKERS_INTERVAL = PeerNode.CHECK_FOR_SWAPPED_TRACKERS_INTERVAL;
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	public PeerConnection(TransportPlugin transportPlugin, PeerNode pn, PluginAddress detectedTransportAddress){
		this.transportPlugin = transportPlugin;
		this.transportName = transportPlugin.transportName;
		this.pn = pn;
		this.detectedTransportAddress = detectedTransportAddress;
	}
	
	protected synchronized void maybeSwapTrackers() {
		if(currentTracker == null || previousTracker == null) return;
		if(currentTracker.packets == previousTracker.packets) return;
		long delta = Math.abs(currentTracker.packets.createdTime - previousTracker.packets.createdTime);
		if(previousTracker != null && (!previousTracker.packets.isDeprecated()) &&
				delta < CHECK_FOR_SWAPPED_TRACKERS_INTERVAL) {
			// Swap prev and current iff H(new key) > H(old key).
			// To deal with race conditions (node A gets 1 current 2 prev, node B gets 2 current 1 prev; when we rekey we lose data and cause problems).

			// FIXME since this is a key dependancy, it needs to be looked at.
			// However, an attacker cannot get this far without knowing the privkey, so it's unlikely to be an issue.

			MessageDigest md = SHA256.getMessageDigest();
			md.update(currentTracker.outgoingKey);
			md.update(currentTracker.incommingKey);
			md.update(TEST_AS_BYTES);
			md.update(Fields.longToBytes(pn.getBootID() ^ pn.node.bootID));
			int curHash = Fields.hashCode(md.digest());
			md.reset();

			md.update(previousTracker.outgoingKey);
			md.update(previousTracker.incommingKey);
			md.update(TEST_AS_BYTES);
			md.update(Fields.longToBytes(pn.getBootID() ^ pn.node.bootID));
			int prevHash = Fields.hashCode(md.digest());
			SHA256.returnMessageDigest(md);

			if(prevHash < curHash) {
				// Swap over
				SessionKey temp = previousTracker;
				previousTracker = currentTracker;
				currentTracker = temp;
				if(logMINOR) Logger.minor(this, "Swapped SessionKey's on "+this+" cur "+currentTracker+" prev "+previousTracker+" delta "+delta+" cur.deprecated="+currentTracker.packets.isDeprecated()+" prev.deprecated="+previousTracker.packets.isDeprecated());
			} else {
				if(logMINOR) Logger.minor(this, "Not swapping SessionKey's on "+this+" cur "+currentTracker+" prev "+previousTracker+" delta "+delta+" cur.deprecated="+currentTracker.packets.isDeprecated()+" prev.deprecated="+previousTracker.packets.isDeprecated());
			}
		} else {
			if (logMINOR)
				Logger.minor(this, "Not swapping SessionKey's: previousTracker = " + previousTracker.toString()
				        + (previousTracker.packets.isDeprecated() ? " (deprecated)" : "") + " time delta = " + delta);
		}
	}
	
}
