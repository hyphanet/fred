package freenet.node;

import java.util.Vector;

import freenet.pluginmanager.PluginAddress;
/**
 * Base object of PeerPacketConnection and PeerStreamConnection. Fields common to both are present.
 * @author chetan
 *
 */
public class PeerConnection {
	
	protected final String transportName; 
	
	/** Every connection can belong to only one peernode. */
	protected final PeerNode pn;
	
	/** The peer it connects to */
	protected PluginAddress detectedTransportAddress;
	
	/** List of keys for every connection. Multiple setups might complete simultaneously.
	 * This will also be used to replace current, previous and unverified to make it more generic.
	 */
	private Vector<SessionKey> keys = new Vector<SessionKey> ();
	
	private SessionKey currentTracker = null;
	private SessionKey previousTracker = null;
	private SessionKey unverifiedTracker = null;
	
	public PeerConnection(String transportName, PeerNode pn){
		this.transportName = transportName;
		this.pn = pn;
	}
	
	/*
	 * Custom implementation with several keys can be provided here.
	 */
	public synchronized void addKey(SessionKey key){
		keys.add(key);
	}
	
	public synchronized SessionKey setKey(SessionKey key, int position){
		return keys.set(position, key);
	}
	
	public synchronized Vector<SessionKey> getKeys(){
		return keys;
	}
	
	public int getKeysSize(){
		return keys.size();
	}
	
	/*
	 * The old logic can be employed here.
	 */

	public synchronized SessionKey getCurrentKeyTracker() {
		return currentTracker;
	}
	
	public synchronized SessionKey getPreviousKeyTracker() {
		return previousTracker;
	}
	
	public synchronized SessionKey getUnverifiedKeyTracker() {
		return unverifiedTracker;
	}
	
	public synchronized void setCurrentKeyTracker(SessionKey currentTracker) {
		this.currentTracker = currentTracker;
	}
	
	public synchronized void setPreviousKeyTracker(SessionKey previousTracker) {
		this.previousTracker = previousTracker;
	}
	
	public synchronized void setUnverifiedKeyTracker(SessionKey unverifiedTracker) {
		this.unverifiedTracker = unverifiedTracker;
	}
	
}
