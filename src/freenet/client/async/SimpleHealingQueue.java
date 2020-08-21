/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;
import java.util.Map;

import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestClientBuilder;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class SimpleHealingQueue extends BaseClientPutter implements HealingQueue, PutCompletionCallback {
	private static final long serialVersionUID = -2884613086588264043L;

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

	static final RequestClient REQUEST_CLIENT = new RequestClientBuilder().build();

	public SimpleHealingQueue(InsertContext context, short prio, int maxRunning) {
		super(prio, REQUEST_CLIENT);
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
							CHKBlock.DATA_LENGTH, ctr, false, false, data, context, false, true, 0, cryptoAlgorithm, cryptoKey);
			} catch (Throwable e) {
				Logger.error(this, "Caught trying to insert healing block: "+e, e);
				return false;
			}
			runningInserters.put(data, sbi);
		}
		try {
			sbi.schedule(context);
			if(logMINOR)
				Logger.minor(this, "Started healing insert "+ctr+" for "+data);
			return true;
		} catch (Throwable e) {
			Logger.error(this, "Caught trying to insert healing block: "+e, e);
			return false;
		}
	}

	@Override
	public void queue(Bucket data, byte[] cryptoKey, byte cryptoAlgorithm, ClientContext context) {
		if(!innerQueue(data, cryptoKey, cryptoAlgorithm, context))
			data.free();
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
	protected void innerNotifyClients(ClientContext context) {
		// Do nothing
	}

	@Override
	public void onSuccess(ClientPutState state, ClientContext context) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(logMINOR)
			Logger.minor(this, "Successfully inserted healing block: "+sbi.getURINoEncode()+" for "+data+" ("+sbi.token+ ')');
		data.free();
	}

	@Override
	public void onFailure(InsertException e, ClientPutState state, ClientContext context) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		Bucket data = (Bucket) sbi.getToken();
		synchronized(this) {
			runningInserters.remove(data);
		}
		if(logMINOR)
			Logger.minor(this, "Failed to insert healing block: "+sbi.getURINoEncode()+" : "+e+" for "+data+" ("+sbi.token+ ')', e);
		data.free();
	}

	@Override
	public void onEncode(BaseClientKey usk, ClientPutState state, ClientContext context) {
		// Ignore
	}

	@Override
	public void onTransition(ClientPutState oldState, ClientPutState newState, ClientContext context) {
		// Should never happen
		Logger.error(this, "impossible: onTransition on SimpleHealingQueue from "+oldState+" to "+newState, new Exception("debug"));
	}

	@Override
	public void onMetadata(Metadata m, ClientPutState state, ClientContext context) {
		// Should never happen
		Logger.error(this, "Got metadata on SimpleHealingQueue from "+state+": "+m, new Exception("debug"));
	}

	@Override
	public void onBlockSetFinished(ClientPutState state, ClientContext context) {
		// Ignore
	}

	@Override
	public void onFetchable(ClientPutState state) {
		// Ignore
	}

	@Override
	public void onTransition(ClientGetState oldState, ClientGetState newState, ClientContext context) {
		// Ignore
	}

	@Override
	protected void innerToNetwork(ClientContext context) {
		// Ignore
	}

	@Override
	public void cancel(ClientContext context) {
		super.cancel();
	}

	@Override
	public int getMinSuccessFetchBlocks() {
		return 0;
	}

	@Override
	public void onMetadata(Bucket meta, ClientPutState state,
			ClientContext context) {
		Logger.error(this, "onMetadata() in SimpleHealingQueue - impossible", new Exception("error"));
		meta.free();
	}

    @Override
    public void innerOnResume(ClientContext context) {
        // Do nothing. Not persisted.
    }

    @Override
    protected ClientBaseCallback getCallback() {
        return null;
    }
}
