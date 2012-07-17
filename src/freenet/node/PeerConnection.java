package freenet.node;

import java.util.Vector;

import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.TransportPlugin;
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
	
	public PeerConnection(TransportPlugin transportPlugin, PeerNode pn, PluginAddress detectedTransportAddress){
		this.transportPlugin = transportPlugin;
		this.transportName = transportPlugin.transportName;
		this.pn = pn;
		this.detectedTransportAddress = detectedTransportAddress;
	}
	
}
