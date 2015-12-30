package freenet.node;

import java.lang.ref.WeakReference;

import freenet.keys.Key;

/** Methods on PeerNode that don't need any significant locking. Used by FailureTableEntry to 
 * guarantee safety. */
interface PeerNodeUnlocked {
	
	double getLocation();
	
	long getBootID();
	
	void offer(Key key);

	WeakReference<? extends PeerNodeUnlocked> getWeakRef();
	
	public String shortToString();

	boolean isConnected();

}
