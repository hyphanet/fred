package freenet.node;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** 
 * For any single UID, we should only route to the same node once, even if
 * the request comes back to us in a loop. Loop detection with rejection is
 * dangerous, and we can't easily disentangle the various failure modes, so
 * we allow a request to loop back to us and then relay it forward, RNFing
 * if appropriate. So we keep a UIDRoutingContext for each UID that has live
 * UIDTag's.
 * 
 * @author toad
 */
public class UIDRoutingContextTracker {
	
	private final HashMap<Long,UIDRoutingContext> routingContexts =
		new HashMap<Long,UIDRoutingContext>();
	
	public class UIDRoutingContext {
		
		/** The UID of all the tags */
		public final Long uid;
		/** If true, tags will be for inserts, and no offer tags. */
		public final boolean insert;
		/** Request or insert tags */
		private final ArrayList<UIDTag> tags;
		/** The set of nodes this UID has been routed to. This will always
		 * be very small. */
		private final Set<WeakReference<PeerNode>> routedTo;
		private boolean finished;

		UIDRoutingContext(long uid, boolean insert) {
			this.uid = uid;
			this.insert = insert;
			tags = new ArrayList<UIDTag>();
			routedTo = new HashSet<WeakReference<PeerNode>>();
			finished = false;
		}
		
		public void add(UIDTag tag) {
			synchronized(UIDRoutingContextTracker.this) {
				if(finished) throw new IllegalStateException();
				if(!tags.contains(tag))
					tags.add(tag);
			}
		}

		public void remove(UIDTag tag) {
			synchronized(UIDRoutingContextTracker.this) {
				tags.remove(tag);
				if(tags.isEmpty())
					removeMe();
			}
		}

		private void removeMe() {
			finished = true;
			routingContexts.remove(uid);
		}

	}

}
