/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Map;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class SimpleHealingQueue extends BaseClientPutter implements HealingQueue, PutCompletionCallback {

	final int maxRunning;
	int counter;
	InsertContext ctx;
	final Map<Bucket, SingleBlockInserter> runningInserters;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public SimpleHealingQueue(InsertContext context, short prio, int maxRunning) {
		super(prio, new RequestClient() {
			public boolean persistent() {
				return false;
			}
			public void removeFrom(ObjectContainer container) {
				throw new UnsupportedOperationException();
			}
			public boolean realTimeFlag() {
				return false;
			} });
		this.ctx = context;
		this.runningInserters = new HashMap<Bucket, SingleBlockInserter>();
		this.maxRunning = maxRunning;
	}

	public boolean innerQueue(Bucket data, byte[] cryptoKey, byte cryptoAlgorithm, ClientContext context) {
		SingleBlockInserter sbi;
		int ctr;
		synchronized(this) {
			ctr = counter++;
			if(runningInserters.size() > maxRunning) return false;
			try {
				sbi = new SingleBlockInserter(this, data, (short)-1,
							FreenetURI.EMPTY_CHK_URI, ctx, realTimeFlag, this, false,
							CHKBlock.DATA_LENGTH, ctr, false, false, false, data, null, context, false, true, 0, cryptoAlgorithm, cryptoKey);
			} catch (Throwable e) {
				Logger.error(this, "Caught trying to insert healing block: "+e, e);
				return false;
			}
			runningInserters.put(data, sbi);
		}
		try {
			sbi.schedule(null, context);
			if(logMINOR)
				Logger.minor(this, "Started healing insert "+ctr+" for "+data);
			return true;
		} catch (Throwable e) {
			Logger.error(this, "Caught trying to insert healing block: "+e, e);
			return false;
		}
	}

	public void queue(Bucket data, byte[] cryptoKey, byte cryptoAlgorithm, ClientContext context) {
		if(!innerQueue(data, cryptoKey, cryptoAlgorithm, context))
			data.free();
	}

	@Override
	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	@Override
	public FreenetURI getURI() {
		return FreenetURI.EMPTY_CHK_URI;
	}

	@Override
	public boolean isFinished() {
		return false;
	}

	@Override
	public void notifyClients(ObjectContainer container, ClientContext context) {
		// Do nothing
	}

	public void onSuccess(ClientPutState state, ObjectContainer container, ClientContext context) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(logMINOR)
			Logger.minor(this, "Successfully inserted healing block: "+sbi.getURINoEncode()+" for "+data+" ("+sbi.token+ ')');
		data.free();
	}

	public void onFailure(InsertException e, ClientPutState state, ObjectContainer container, ClientContext context) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(logMINOR)
			Logger.minor(this, "Failed to insert healing block: "+sbi.getURINoEncode()+" : "+e+" for "+data+" ("+sbi.token+ ')', e);
		data.free();
	}

	public void onEncode(BaseClientKey usk, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState, ObjectContainer container) {
		// Should never happen
		Logger.error(this, "impossible: onTransition on SimpleHealingQueue from "+oldState+" to "+newState, new Exception("debug"));
	}

	public void onMetadata(Metadata m, ClientPutState state, ObjectContainer container, ClientContext context) {
		// Should never happen
		Logger.error(this, "Got metadata on SimpleHealingQueue from "+state+": "+m, new Exception("debug"));
	}

	public void onBlockSetFinished(ClientPutState state, ObjectContainer container, ClientContext context) {
		// Ignore
	}

	public void onFetchable(ClientPutState state, ObjectContainer container) {
		// Ignore
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
		// Ignore
	}

	@Override
	protected void innerToNetwork(ObjectContainer container, ClientContext context) {
		// Ignore
	}

	@Override
	public void cancel(ObjectContainer container, ClientContext context) {
		super.cancel();
	}

	@Override
	public int getMinSuccessFetchBlocks() {
		return 0;
	}

}
