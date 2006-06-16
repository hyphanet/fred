package freenet.node;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.ArrayBucket;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Fetch an ARK. Permanent, tied to a PeerNode, stops itself after a successful fetch.
 */
public class ARKFetcher implements ClientCallback {

	final PeerNode peer;
	final Node node;
	private ClientGetter getter;
	private FreenetURI fetchingURI;
	private boolean shouldRun = false;
	private static final int MAX_BACKOFF = 60*60*1000;  // 1 hour
	private static final int MIN_BACKOFF = 5*1000;  // 5 seconds
	private int backoff = MIN_BACKOFF;
	private String identity;
	private boolean isFetching = false;
	private boolean started = false;
	private long startedEdition;

	public ARKFetcher(PeerNode peer, Node node) {
		this.peer = peer;
		this.node = node;
		this.identity = peer.getIdentityString();
	}

	/**
	 * Called when we fail to connect twice after a new reference. (So we get one from
	 * the ARK, we wait for the current connect attempt to fail, we start another one,
	 * that fails, we start another one, that also fails, so we try the fetch again to
	 * see if we can find something more recent).
	 */
	public synchronized void queue() {
		if(node.arkFetchManager.hasReadyARKFetcher(this)) {
			return;
		}
		if(peer.isConnected()) {
			return;
		}
		if(isFetching) {
			return;
		}
		Logger.normal( this, "Queueing ARK Fetcher after "+peer.getHandshakeCount()+" failed handshakes for "+peer.getPeer()+" with identity '"+peer.getIdentityString()+"'");
		node.arkFetchManager.addReadyARKFetcher(this);
	}

	/**
	 * Called when the ARKFetchManager says it's our turn to start fetching.
	 */
	public synchronized void start() {
		if(node.arkFetchManager.hasReadyARKFetcher(this)) {
			node.arkFetchManager.removeReadyARKFetcher(this);
		}
		if(peer.isConnected()) {
			return;
		}
		if(isFetching) {
			return;
		}
		ClientGetter cg = null;
		if(started) {  // We only need one ARKFetcher per PeerNode
		  return;
		}
		Logger.minor(this, "Starting ... for "+peer+" on "+this);
		Logger.normal( this, "Starting ARK Fetcher after "+peer.getHandshakeCount()+" failed handshakes for "+peer.getPeer()+" with identity '"+peer.getIdentityString()+"'");
		started = true;
		// Start fetch
		shouldRun = true;
		if(getter == null) {
			USK ark = peer.getARK();
			if(ark == null) {
				return;
			}
			FreenetURI uri = ark.getURI();
			startedEdition = uri.getSuggestedEdition();
			fetchingURI = uri;
			Logger.minor(this, "Fetching ARK: "+uri+" for "+peer);
			cg = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, 
					uri, node.arkFetcherContext, RequestStarter.UPDATE_PRIORITY_CLASS, 
					this, new ArrayBucket());
			getter = cg;
		} else return; // already running
		
		if(cg != null)
			try {
				synchronized(this) {
					if(!isFetching) {
						node.addARKFetcher(identity,this);
						isFetching = true;
					}
				}
				cg.start();
			} catch (FetchException e) {
				onFailure(e, cg);
			}
	}
	
	/**
	 * Called when the node connects successfully.
	 */
	public synchronized void stop() {
		// Stop fetch
		backoff = MIN_BACKOFF;
		Logger.minor(this, "Cancelling ARK fetch for "+peer);
		shouldRun = false;
		synchronized(this){
			started = false;
			if(isFetching) {
				node.removeARKFetcher(identity,this);
				if(node.arkFetchManager.hasReadyARKFetcher(this)) {
					node.arkFetchManager.removeReadyARKFetcher(this);
				}
				isFetching = false;
			}
		}
		if(getter != null)
			getter.cancel();
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		Logger.minor(this, "Fetched ARK for "+peer, new Exception("debug"));
		synchronized(this) {
			started = false;
			// Fetcher context specifies an upper bound on size.
			backoff = MIN_BACKOFF;
			
			if(isFetching) {
				node.removeARKFetcher(identity,this);
				if(node.arkFetchManager.hasReadyARKFetcher(this)) {
					node.arkFetchManager.removeReadyARKFetcher(this);
				}
				isFetching = false;
			}
			
			getter = null;
		}
		
		ArrayBucket bucket = (ArrayBucket) result.asBucket();
		byte[] data = bucket.toByteArray();
		String ref;
		try {
			ref = new String(data, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			// Yeah, right.
			throw new Error(e);
		}
		SimpleFieldSet fs;
		try {
			fs = new SimpleFieldSet(ref, true);
			Logger.minor(this, "Got ARK for "+peer.getPeer());
			peer.gotARK(fs, startedEdition);
		} catch (IOException e) {
			// Corrupt ref.
			Logger.error(this, "Corrupt ARK reference? Fetched "+fetchingURI+" got while parsing: "+e+" from:\n"+ref, e);
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {
		synchronized(this) {
			started = false;
			Logger.minor(this, "Failed to fetch ARK for "+peer+" : "+e, e);
			
			if(isFetching) {
				node.removeARKFetcher(identity,this);
				if(node.arkFetchManager.hasReadyARKFetcher(this)) {
					node.arkFetchManager.removeReadyARKFetcher(this);
				}
				isFetching = false;
			}
			
			// If it's a redirect, follow the redirect and update the ARK.
			// If it's any other error, wait a while then retry.
			getter = null;
			if(!shouldRun) return;
			if(e.newURI == null) {
				backoff += backoff;
				if(backoff > MAX_BACKOFF) backoff = MAX_BACKOFF;
			}
		}
		if(e.newURI != null) {
			Logger.minor(this, "Failed to fetch ARK for "+peer.getPeer()+", "+fetchingURI+" gave redirect to "+e.newURI);
			peer.updateARK(e.newURI);
			queueWithBackoff();
			return;
		}
		Logger.minor(this, "Failed to fetch ARK for "+peer.getPeer()+", now backing off ARK fetches for "+(int) (backoff / 1000)+" seconds");
		// We may be on the PacketSender thread.
		// FIXME should this be exponential backoff?
		queueWithBackoff();
	}

	public void onSuccess(BaseClientPutter state) {
		// Impossible.
		Logger.error(this, "Impossible reached in ARKFetcher.onSuccess(BaseClientPutter) for peer "+peer.getPeer(), new Exception("error"));
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Impossible.
		Logger.error(this, "Impossible reached in ARKFetcher.onFailure(InserterException,BaseClientPutter) for peer "+peer.getPeer(), new Exception("error"));
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Impossible.
		Logger.error(this, "Impossible reached in ARKFetcher.onGeneratredURI(FreenetURI,BaseClientPutter) for peer "+peer.getPeer(), new Exception("error"));
	}

	public boolean isFetching() {
		return isFetching;
	}
	
	/**
	 * Queue a Runnable on the PacketSender timed job queue to be run almost immediately
	 * Should not be called from other objects except for ARKFetchManager
	 */
	public void queueRunnableImmediately() {
		node.ps.queueTimedJob(new Runnable() { public void run() { start(); }}, 100);  // Runnable rather than FastRunnable so we don't put it on the PacketSender thread
	}
	
	/**
	 * Queue a call to our queue method on the PacketSender timed job queue to be run after our ARK fetch backoff expires
	 */
	private void queueWithBackoff() {
		node.ps.queueTimedJob(new Runnable() { public void run() { queue(); }}, backoff);  // Runnable rather than FastRunnable so we don't put it on the PacketSender thread
	}
}
