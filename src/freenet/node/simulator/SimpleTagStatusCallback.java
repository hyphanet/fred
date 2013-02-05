package freenet.node.simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import freenet.node.NodeDispatcher.TagStatusCallback;
import freenet.node.UIDTag;

/** Track statuses across multiple nodes. When a tag completes on all nodes it is running on, log
 * a line to standard output which shows which nodes it went to.
 * 
 * Note that this leaks! Only for testing!
 * @author toad
 */
public class SimpleTagStatusCallback implements TagStatusCallback {

	private final Map<Long, TagStatus> tracker =
		new HashMap<Long, TagStatus>();
	
	private int runningCount;
	private int finishedCount;
	
	private class TagStatus {
		boolean finished;
		final boolean insert;
		final long uid;
		final List<Integer> nodesVisited;
		final Set<Integer> nodesRunningOn;
		TagStatus(long uid, boolean insert) {
			this.uid = uid;
			this.insert = insert;
			nodesVisited = new ArrayList<Integer>();
			nodesRunningOn = new HashSet<Integer>();
		}
		void accepted(int nodeID) {
			if(finished) System.err.println("ERROR: Finished request "+uid+" accepted by "+nodeID);
			else {
				nodesVisited.add(nodeID);
				nodesRunningOn.add(nodeID);
			}
		}
		void rejected(int nodeID) {
			if(finished) System.err.println("ERROR: Finished request "+uid+" rejected by "+nodeID);
			else {
				nodesVisited.add(-nodeID);
			}
		}
		void completed(int nodeID) {
			if(finished) {
				System.err.println("ERROR: Finished request "+uid+" completed by "+nodeID);
				return;
			}
			if(!nodesRunningOn.remove(nodeID)) {
				System.err.println("Completed "+nodeID+" but wasn't running on it?");
			} else {
				if(nodesRunningOn.isEmpty()) {
					finished = true;
					runningCount--;
					finishedCount++;
					StringBuffer sb = new StringBuffer();
					if(insert)
						sb.append("Insert:  ");
					else
						sb.append("Request: ");
					sb.append(uid);
					sb.append(": ");
					for(int i : nodesVisited) {
						if(i < 0) {
							sb.append(-i); // Rejected.
							sb.append("*");
						} else {
							sb.append(i);
						}
						sb.append(" ");
					}
					sb.append("(");
					sb.append(runningCount);
					sb.append("run ");
					sb.append(finishedCount);
					sb.append("fin)");
					System.out.println(sb.toString());
				}
			}
		}
	}
	
	private synchronized TagStatus makeTag(UIDTag tag) {
		long uid = tag.uid;
		TagStatus status = tracker.get(uid);
		if(status != null) return status;
		status = new TagStatus(uid, tag.isInsert());
		tracker.put(uid, status);
		runningCount++;
		return status;
	}

	@Override
	public synchronized void accepted(int nodeID, UIDTag tag) {
		makeTag(tag).accepted(nodeID);
	}

	@Override
	public void rejected(int nodeID, UIDTag tag) {
		makeTag(tag).rejected(nodeID);
	}

	@Override
	public void completed(int nodeID, UIDTag tag) {
		makeTag(tag).completed(nodeID);
	}

}
