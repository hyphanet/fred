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
	private static final int MAX_BACKOFF = 60*60*1000;
	private static final int MIN_BACKOFF = 5*1000;
	private int backoff = MIN_BACKOFF;
	private String identity;
	private boolean isFetching = false;

	public ARKFetcher(PeerNode peer, Node node) {
		this.peer = peer;
		this.node = node;
		this.identity = peer.getIdentityString();
	}

	/**
	 * Called when the node starts / is added, and also when we fail to connect twice 
	 * after a new reference. (So we get one from the ARK, we wait for the current
	 * connect attempt to fail, we start another one, that fails, we start another one,
	 * that also fails, so we try the fetch again to see if we can find something more
	 * recent).
	 */
	public void start() {
		ClientGetter cg = null;
		synchronized(this) {
			// Start fetch
			shouldRun = true;
			if(getter == null) {
				USK ark = peer.getARK();
				if(ark == null) {
					return;
				}
				FreenetURI uri = ark.getURI();
				fetchingURI = uri;
				Logger.minor(this, "Fetching ARK: "+uri+" for "+peer);
				cg = new ClientGetter(this, node.chkFetchScheduler, node.sskFetchScheduler, 
						uri, node.arkFetcherContext, RequestStarter.INTERACTIVE_PRIORITY_CLASS, 
						this, new ArrayBucket());
				getter = cg;
			} else return; // already running
		}
		if(cg != null)
			try {
				cg.start();
				if(!isFetching) {
					node.addARKFetcher(identity,this);
					isFetching = true;
				}
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
		if(isFetching) {
			node.removeARKFetcher(identity,this);
			isFetching = false;
		}
		if(getter != null)
			getter.cancel();
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		Logger.minor(this, "Fetched ARK for "+peer, new Exception("debug"));
		// Fetcher context specifies an upper bound on size.
		backoff = MIN_BACKOFF;
		if(isFetching) {
			synchronized(this){
				node.removeARKFetcher(identity,this);
				isFetching = false;
			}
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
			peer.gotARK(fs);
		} catch (IOException e) {
			// Corrupt ref.
			Logger.error(this, "Corrupt ARK reference? Fetched "+fetchingURI+" got while parsing: "+e+" from:\n"+ref, e);
		}
	}

	public void onFailure(FetchException e, ClientGetter state) {
		Logger.minor(this, "Failed to fetch ARK for "+peer+" : "+e, e);
		if(isFetching) {
			synchronized(this){
				node.removeARKFetcher(identity,this);
				isFetching = false;
			}
		}
		// If it's a redirect, follow the redirect and update the ARK.
		// If it's any other error, wait a while then retry.
		getter = null;
		if(!shouldRun) return;
		if(e.newURI != null) {
			peer.updateARK(e.newURI);
			start();
			return;
		}
		backoff += backoff;
		if(backoff > MAX_BACKOFF) backoff = MAX_BACKOFF;
		Logger.minor(this, "Failed to fetch ARK for "+peer+", now backing off ARK fetches for "+(int) (backoff / 1000)+" seconds");
		// We may be on the PacketSender thread.
		// FIXME should this be exponential backoff?
		node.ps.queueTimedJob(new FastRunnable() { public void run() { start(); }}, backoff);
	}

	public void onSuccess(BaseClientPutter state) {
		// Impossible.
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Impossible.
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Impossible.
	}
	
}
