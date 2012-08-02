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
	//private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				//logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	public PeerConnection(TransportPlugin transportPlugin, PeerNode pn, PluginAddress detectedTransportAddress){
		this.transportPlugin = transportPlugin;
		this.transportName = transportPlugin.transportName;
		this.pn = pn;
		this.detectedTransportAddress = detectedTransportAddress;
	}
	
}
