package freenet.client.async;

import java.util.HashMap;

import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.io.Bucket;

public class SimpleHealingQueue extends BaseClientPutter implements HealingQueue, PutCompletionCallback {

	final int maxRunning;
	int counter;
	InserterContext ctx;
	final HashMap runningInserters;
	
	public SimpleHealingQueue(ClientRequestScheduler scheduler, InserterContext context, short prio, int maxRunning) {
		super(prio, scheduler, null, context);
		this.ctx = context;
		this.runningInserters = new HashMap();
		this.maxRunning = maxRunning;
	}

	public boolean innerQueue(Bucket data) {
		SingleBlockInserter sbi;
		int ctr;
		synchronized(this) {
			ctr = counter++;
			if(runningInserters.size() > maxRunning) return false;
			try {
				sbi = new SingleBlockInserter(this, data, (short)-1, 
							FreenetURI.EMPTY_CHK_URI, ctx, this, false, 
							CHKBlock.DATA_LENGTH, ctr, false, false, false, data);
			} catch (Throwable e) {
				Logger.error(this, "Caught trying to insert healing block: "+e, e);
				return false;
			}
			runningInserters.put(data, sbi);
		}
		try {
			sbi.schedule();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Started healing insert "+ctr+" for "+data);
			return true;
		} catch (Throwable e) {
			Logger.error(this, "Caught trying to insert healing block: "+e, e);
			return false;
		}
	}

	public void queue(Bucket data) {
		if(!innerQueue(data))
			data.free();
	}
	
	public void onMajorProgress() {
		// Ignore
	}

	public FreenetURI getURI() {
		return FreenetURI.EMPTY_CHK_URI;
	}

	public boolean isFinished() {
		return false;
	}

	public void notifyClients() {
		// Do nothing
	}

	public void onSuccess(ClientPutState state) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Successfully inserted healing block: "+sbi.getURI()+" for "+data+" ("+sbi.token+")");
		data.free();
	}

	public void onFailure(InserterException e, ClientPutState state) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Failed to insert healing block: "+sbi.getURI()+" : "+e+" for "+data+" ("+sbi.token+")", e);
		data.free();
	}

	public void onEncode(BaseClientKey usk, ClientPutState state) {
		// Ignore
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		// Should never happen
		Logger.error(this, "impossible: onTransition on SimpleHealingQueue from "+oldState+" to "+newState, new Exception("debug"));
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		// Should never happen
		Logger.error(this, "Got metadata on SimpleHealingQueue from "+state+": "+m, new Exception("debug"));
	}

	public void onBlockSetFinished(ClientPutState state) {
		// Ignore
	}

	public void onFetchable(ClientPutState state) {
		// Ignore
	}

}
