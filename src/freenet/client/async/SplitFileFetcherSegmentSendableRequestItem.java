package freenet.client.async;

import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestItemKey;

public class SplitFileFetcherSegmentSendableRequestItem implements
		SendableRequestItem, SendableRequestItemKey {

	final int blockNum;
	
	public SplitFileFetcherSegmentSendableRequestItem(int x) {
		this.blockNum = x;
	}

	@Override
	public void dump() {
		// Ignore, we will be GC'ed
	}

	@Override
	public SendableRequestItemKey getKey() {
		return this;
	}
	
	public int hashCode() {
		return blockNum;
	}
	
	public boolean equals(Object o) {
		if(o instanceof SplitFileFetcherSegmentSendableRequestItem) {
			return ((SplitFileFetcherSegmentSendableRequestItem)o).blockNum == blockNum;
		} else return false;
	}

}
