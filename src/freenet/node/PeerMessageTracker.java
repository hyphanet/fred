package freenet.node;

import java.util.ArrayList;
import java.util.HashMap;

import freenet.io.comm.DMT;

/**
 * This class has been created to isolate the Message tracking portions of code in NewPacketFormat
 * @author chetan
 *
 */
public class PeerMessageTracker {
	
	/** 
	 * The actual buffer of outgoing messages that have not yet been acked.
	 * LOCKING: Protected by sendBufferLock. 
	 */
	private final ArrayList<HashMap<Integer, MessageWrapper>> startedByPrio;
	
	public final PeerNode pn;
	
	public PeerMessageTracker(PeerNode pn) {
		
		this.pn = pn;
		
		startedByPrio = new ArrayList<HashMap<Integer, MessageWrapper>>(DMT.NUM_PRIORITIES);
		for(int i = 0; i < DMT.NUM_PRIORITIES; i++) {
			startedByPrio.add(new HashMap<Integer, MessageWrapper>());
		}
	}
	
	public MessageFragment getMessageFragment(int messageLength) {
		return null;
	}

}
