package freenet.client.async;

import freenet.keys.USK;
import freenet.support.Logger;

/** Proxy class to only pass through latest-slot updates after an onRoundFinished().
 * Note that it completely ignores last-known-good updates.
 * @author toad
 */
public class USKSparseProxyCallback implements USKProgressCallback {

	final USKCallback target;
	final USK key;

	private long lastEdition;
	private long lastSent;
	private boolean lastMetadata;
	private short lastCodec;
	private byte[] lastData;
	private boolean lastWasKnownGoodToo;
	private boolean roundFinished;
	
    private static volatile boolean logMINOR;
	static {
		Logger.registerClass(USKSparseProxyCallback.class);
	}

	public USKSparseProxyCallback(USKCallback cb, USK key) {
		target = cb;
		lastEdition = -1; // So we see the first one even if it's 0
		lastSent = -1;
		this.key = key;
		if(logMINOR) Logger.minor(this, "Creating sparse proxy callback "+this+" for "+cb+" for "+key);
	}

	@Override
	public void onFoundEdition(long l, USK key, 
			ClientContext context, boolean metadata, short codec, byte[] data,
			boolean newKnownGood, boolean newSlotToo) {
		synchronized(this) {
			if(l < lastEdition) {
				if(!roundFinished) return;
				if(!newKnownGood) return;
			} else if(l == lastEdition) {
				if(newKnownGood) lastWasKnownGoodToo = true;
			} else {
				lastEdition = l;
				lastMetadata = metadata;
				lastCodec = codec;
				lastData = data;
				lastWasKnownGoodToo = newKnownGood;
			}
			if(!roundFinished) return;
		}
		target.onFoundEdition(l, key, context, metadata, codec, data, newKnownGood, newSlotToo);
	}

	@Override
	public short getPollingPriorityNormal() {
		return target.getPollingPriorityNormal();
	}

	@Override
	public short getPollingPriorityProgress() {
		return target.getPollingPriorityProgress();
	}

	@Override
	public void onSendingToNetwork(ClientContext context) {
		innerRoundFinished(context, false);
	}

	@Override
	public void onRoundFinished(ClientContext context) {
		innerRoundFinished(context, true);
	}
	
	private void innerRoundFinished(ClientContext context, boolean finishedRound) {
		long ed;
		boolean meta;
		short codec;
		byte[] data;
		boolean wasKnownGood;
		synchronized(this) {
			if(finishedRound)
				roundFinished = true;
			if(lastSent == lastEdition) return;
			lastSent = ed = lastEdition;
			meta = lastMetadata;
			codec = lastCodec;
			data = lastData;
			wasKnownGood = lastWasKnownGoodToo;
		}
		if(ed == -1) {
			ed = context.uskManager.lookupLatestSlot(key);
			if(ed == -1) return;
			meta = false;
			codec = -1;
			data = null;
			wasKnownGood = false;
		}
		if(ed == -1) return;
		target.onFoundEdition(ed, key, context, meta, codec, data, wasKnownGood, wasKnownGood);
	}

}
