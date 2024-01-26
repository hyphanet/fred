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
import freenet.node.Location;
import freenet.node.NodeStarter;
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
	private final double nodeLocation;
	private final boolean opennetEnabled;
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

	public SimpleHealingQueue(InsertContext context, short prio, int maxRunning, double nodeLocation, boolean opennetEnabled) {
		super(prio, REQUEST_CLIENT);
		this.ctx = context;
		this.nodeLocation = nodeLocation;
		this.opennetEnabled = opennetEnabled;
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
			if (isHealingThisBlockSimilarToForwarding(context, sbi)) {
				runningInserters.put(data, sbi);
			}
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

	/**
	 * Specialize Healing to the fraction of the keyspace in which we would receive the inserts
	 * if we were one of 5 long distance nodes of an actual inserter.
	 *
	 * If an opennet node is connected to an attacker, healing traffic could be mistaken for an insert.
	 * Since opennet cannot be fully secured, this should be avoided.
	 * As a solution, we specialize healing inserts to the inserts we would send if we were one of 5 
	 * long distance connections for a node in another part of the keyspace.
	 *
	 * As a welcome side effect, specialized healing inserts should take one hop less to reach the
	 * correct node from which loop detection will stop the insert long before HTL reaches zero.
	 */
	private boolean isHealingThisBlockSimilarToForwarding(
			ClientContext context,
			SingleBlockInserter sbi) {
		// pure darknet is safer against sybil attacks, so we can heal fully
		if (!opennetEnabled) {
			return true;
		}
		// ensure that we have a routing key
		sbi.tryEncode(context);
		double keyLocation = sbi.getKeyNoEncode().getNodeKey().toNormalizedDouble();
		double randomBetweenZeroAndOne = NodeStarter.getGlobalSecureRandom().nextDouble();
		return shouldHealBlock(nodeLocation, keyLocation, randomBetweenZeroAndOne);
	}

	/**
	 * choose fraction by probabilistic dropping of far away keys.
	 * only enqueue keys in our 20% of the keyspace: the ones that would reach us if we were one of
	 * 5 long distance peers of a peer node.
	 */
	static boolean shouldHealBlock(
			double nodeLocation,
			double keyLocation,
			double randomBetweenZeroAndOne) {
		double distanceToNodeLocation = Location.distance(nodeLocation, keyLocation);
		// accept half the healings in our 20% of the keyspace.
		// If the key is inside "our" 20% of the keyspace, heal it with 50% probability.
		if (distanceToNodeLocation < 0.1) {
			// accept 50%, specialized to our own location (0.5 ** 4 ~ 0.0625). Accept 70% which are going
			// to our short distance peers (0.32 ** 4 ~ 0.01), 78% of those which could be reached via a 
			// direct short distance FOAF (distance 0.02).
			double randomToPower4 = Math.pow(randomBetweenZeroAndOne, 4);
			return distanceToNodeLocation < randomToPower4;
		} else {
			// if the key is a long distance key for us, heal it with 10% probability: it is unlikely that
			// this would have reached us. Setting this to 0 could amplify a keyspace takeover attack.
			return randomBetweenZeroAndOne > 0.9;
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
