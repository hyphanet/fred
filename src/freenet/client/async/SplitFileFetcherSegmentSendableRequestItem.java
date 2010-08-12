package freenet.client.async;

import freenet.node.SendableRequestItem;

public class SplitFileFetcherSegmentSendableRequestItem implements
		SendableRequestItem {

	final int blockNum;
	
	public SplitFileFetcherSegmentSendableRequestItem(int x) {
		this.blockNum = x;
	}

	public void dump() {
		// Ignore, we will be GC'ed
	}

}
