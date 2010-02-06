/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.client.async.ClientPutter;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.SimpleManifestPutter;
import freenet.client.events.ClientEventListener;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.EventLogger;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketTools;
import freenet.support.io.NullBucket;
import freenet.support.io.NullPersistentFileTracker;
import freenet.support.io.PersistentFileTracker;

public class HighLevelSimpleClientImpl implements HighLevelSimpleClient, RequestClient {

	private final short priorityClass;
	private final BucketFactory bucketFactory;
	private final BucketFactory persistentBucketFactory;
	private final PersistentFileTracker persistentFileTracker;
	private final NodeClientCore core;
	/** One CEP for all requests and inserts */
	private final ClientEventProducer eventProducer;
	private long curMaxLength;
	private long curMaxTempLength;
	private int curMaxMetadataLength;
	private final RandomSource random;
	static final int MAX_RECURSION = 10;
	static final int MAX_ARCHIVE_RESTARTS = 2;
	static final int MAX_ARCHIVE_LEVELS = 4;
	static final boolean DONT_ENTER_IMPLICIT_ARCHIVES = true;
	// COOLDOWN_RETRIES-1 so we don't have to wait on the cooldown queue; HLSC is designed
	// for interactive requests mostly.
	/** Number of retries allowed per block in a splitfile. */
	static final int SPLITFILE_BLOCK_RETRIES = Math.min(3, RequestScheduler.COOLDOWN_RETRIES-1);
	/** Number of retries allowed on non-splitfile fetches. */
	static final int NON_SPLITFILE_RETRIES = Math.min(3, RequestScheduler.COOLDOWN_RETRIES-1);
	static final int USK_RETRIES = RequestScheduler.COOLDOWN_RETRIES - 1;
	/** Whether to fetch splitfiles. Don't turn this off! */
	static final boolean FETCH_SPLITFILES = true;
	/** Whether to follow redirects etc. If false, we only fetch a plain block of data. 
	 * Don't turn this off either! */
	static final boolean FOLLOW_REDIRECTS = true;
	/** If set, only check the local datastore, don't send an actual request out.
	 * Don't turn this off either. */
	static final boolean LOCAL_REQUESTS_ONLY = false;
	/** By default, write to the client cache. Turn this off if you are fetching big stuff. */
	static final boolean CAN_WRITE_CLIENT_CACHE = true;
	/** By default, don't write local inserts to the client cache. */
	static final boolean CAN_WRITE_CLIENT_CACHE_INSERTS = false;
	/** Number of retries on inserts */
	static final int INSERT_RETRIES = 10;
	/** Number of RNFs on insert that make a success, or -1 on large networks */
	static final int CONSECUTIVE_RNFS_ASSUME_SUCCESS = 2;
	// going by memory usage only; 4kB per stripe
	static final int MAX_SPLITFILE_BLOCKS_PER_SEGMENT = 1024;
	static final int MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 1536;
	public static final int SPLITFILE_BLOCKS_PER_SEGMENT = 128;
	static final int SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 128;
	
	
	public HighLevelSimpleClientImpl(NodeClientCore node, BucketFactory bf, RandomSource r, short priorityClass, boolean forceDontIgnoreTooManyPathComponents) {
		this.core = node;
		this.priorityClass = priorityClass;
		bucketFactory = bf;
		this.persistentFileTracker = node.persistentTempBucketFactory;
		random = r;
		this.eventProducer = new SimpleEventProducer();
		eventProducer.addEventListener(new EventLogger(Logger.MINOR, false));
		curMaxLength = Long.MAX_VALUE;
		curMaxTempLength = Long.MAX_VALUE;
		curMaxMetadataLength = 1024 * 1024;
		this.persistentBucketFactory = node.persistentTempBucketFactory;
	}
	
	public HighLevelSimpleClientImpl(HighLevelSimpleClientImpl hlsc) {
		this.eventProducer = new SimpleEventProducer();
		this.priorityClass = hlsc.priorityClass;
		this.bucketFactory = hlsc.bucketFactory;
		this.persistentBucketFactory = hlsc.persistentBucketFactory;
		this.persistentFileTracker = hlsc.persistentFileTracker;
		this.core = hlsc.core;
		this.curMaxLength = hlsc.curMaxLength;
		this.curMaxMetadataLength = hlsc.curMaxMetadataLength;
		this.curMaxTempLength = hlsc.curMaxTempLength;
		this.random = hlsc.random;
	}
	
	public HighLevelSimpleClientImpl clone() {
		return new HighLevelSimpleClientImpl(this);
	}
	
	public void setMaxLength(long maxLength) {
		curMaxLength = maxLength;
	}

	public void setMaxIntermediateLength(long maxIntermediateLength) {
		curMaxTempLength = maxIntermediateLength;
	}

	/**
	 * Fetch a key. Either returns the data, or throws an exception.
	 */
	public FetchResult fetch(FreenetURI uri) throws FetchException {
		if(uri == null) throw new NullPointerException();
		FetchContext context = getFetchContext();
		FetchWaiter fw = new FetchWaiter();
		ClientGetter get = new ClientGetter(fw, uri, context, priorityClass, this, null, null);
		try {
			core.clientContext.start(get);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return fw.waitForCompletion();
	}

	public FetchResult fetch(FreenetURI uri, long overrideMaxSize) throws FetchException {
		return fetch(uri, overrideMaxSize, this);
	}

	public FetchResult fetch(FreenetURI uri, long overrideMaxSize, RequestClient clientContext) throws FetchException {
		if(uri == null) throw new NullPointerException();
		FetchWaiter fw = new FetchWaiter();
		FetchContext context = getFetchContext(overrideMaxSize);
		ClientGetter get = new ClientGetter(fw, uri, context, priorityClass, clientContext, null, null);
		try {
			core.clientContext.start(get);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return fw.waitForCompletion();
	}
	
	@Deprecated
	public ClientGetter fetch(FreenetURI uri, long maxSize, RequestClient clientContext, ClientCallback callback, FetchContext fctx) throws FetchException {
		return fetch(uri, maxSize, clientContext, (ClientGetCallback)callback,  fctx);
	}
		
	public ClientGetter fetch(FreenetURI uri, long maxSize, RequestClient clientContext, ClientGetCallback callback, FetchContext fctx) throws FetchException {
		if(uri == null) throw new NullPointerException();
		ClientGetter get = new ClientGetter(callback, uri, fctx, priorityClass, clientContext, null, null);
		try {
			core.clientContext.start(get);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return get;
	}
	
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, String filenameHint) throws InsertException {
		return insert(insert, getCHKOnly, filenameHint, false);
	}
	
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, String filenameHint, boolean isMetadata) throws InsertException {
		InsertContext context = getInsertContext(true);
		PutWaiter pw = new PutWaiter();
		ClientPutter put = new ClientPutter(pw, insert.getData(), insert.desiredURI, insert.clientMetadata,
				context, priorityClass,
				getCHKOnly, isMetadata, this, null, filenameHint, false);
		try {
			core.clientContext.start(put, false);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return pw.waitForCompletion();
	}

	@Deprecated
	public ClientPutter insert(InsertBlock insert, boolean getCHKOnly, String filenameHint, boolean isMetadata, InsertContext ctx, ClientCallback cb) throws InsertException {
		return insert(insert, getCHKOnly, filenameHint, isMetadata, ctx, (ClientPutCallback) cb);
	}

	public ClientPutter insert(InsertBlock insert, boolean getCHKOnly, String filenameHint, boolean isMetadata, InsertContext ctx, ClientPutCallback cb) throws InsertException {
		ClientPutter put = new ClientPutter(cb, insert.getData(), insert.desiredURI, insert.clientMetadata,
				ctx, priorityClass,
				getCHKOnly, isMetadata, this, null, filenameHint, false);
		try {
			core.clientContext.start(put, false);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return put;
	}

	public FreenetURI insertRedirect(FreenetURI insertURI, FreenetURI targetURI) throws InsertException {
		Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, null, null, targetURI, new ClientMetadata());
		Bucket b;
		try {
			b = BucketTools.makeImmutableBucket(bucketFactory, m.writeToByteArray());
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e, e);
			throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
		} catch (MetadataUnresolvedException e) {
			Logger.error(this, "Impossible error: "+e, e);
			throw new InsertException(InsertException.INTERNAL_ERROR, e, null);
		}
		
		InsertBlock block = new InsertBlock(b, null, insertURI);
		return insert(block, false, null, true);
	}

	public FreenetURI insertManifest(FreenetURI insertURI, HashMap bucketsByName, String defaultName) throws InsertException {
		PutWaiter pw = new PutWaiter();
		SimpleManifestPutter putter =
			new SimpleManifestPutter(pw, SimpleManifestPutter.bucketsByNameToManifestEntries(bucketsByName), priorityClass, insertURI, defaultName, getInsertContext(true), false, this, false);
		try {
			core.clientContext.start(putter);
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
		return pw.waitForCompletion();
	}
	
	/**
     * @deprecated Use {@link #addEventHook(ClientEventListener)} instead
     */
    public void addGlobalHook(ClientEventListener listener) {
        addEventHook(listener);
    }

	public void addEventHook(ClientEventListener listener) {
		eventProducer.addEventListener(listener);
	}

	public FetchContext getFetchContext() {
		return getFetchContext(-1);
	}

	public FetchContext getFetchContext(long overrideMaxSize) {
		long maxLength = curMaxLength;
		long maxTempLength = curMaxTempLength;
		if(overrideMaxSize >= 0) {
			maxLength = overrideMaxSize;
			maxTempLength = overrideMaxSize;
		} 
		return 			
			new FetchContext(maxLength, maxTempLength, curMaxMetadataLength, 
				MAX_RECURSION, MAX_ARCHIVE_RESTARTS, MAX_ARCHIVE_LEVELS, DONT_ENTER_IMPLICIT_ARCHIVES, 
				SPLITFILE_BLOCK_RETRIES, NON_SPLITFILE_RETRIES, USK_RETRIES,
				FETCH_SPLITFILES, FOLLOW_REDIRECTS, LOCAL_REQUESTS_ONLY,
				MAX_SPLITFILE_BLOCKS_PER_SEGMENT, MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT,
				bucketFactory, eventProducer, 
				false, CAN_WRITE_CLIENT_CACHE);
	}

	public InsertContext getInsertContext(boolean forceNonPersistent) {
		return new InsertContext(
				INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, 
				eventProducer, CAN_WRITE_CLIENT_CACHE_INSERTS, Node.FORK_ON_CACHEABLE_DEFAULT, Compressor.DEFAULT_COMPRESSORDESCRIPTOR);
	}

	public FreenetURI[] generateKeyPair(String docName) {
		InsertableClientSSK key = InsertableClientSSK.createRandom(random, docName);
		return new FreenetURI[] { key.getInsertURI(), key.getURI() };
	}

	private final ClientGetCallback nullCallback = new NullClientCallback();
	
	public void prefetch(FreenetURI uri, long timeout, long maxSize, Set allowedTypes) {
		prefetch(uri, timeout, maxSize, allowedTypes, RequestStarter.PREFETCH_PRIORITY_CLASS);
	}
	
	public void prefetch(FreenetURI uri, long timeout, long maxSize, Set allowedTypes, short prio) {
		FetchContext ctx = getFetchContext(maxSize);
		ctx.allowedMIMETypes = allowedTypes;
		final ClientGetter get = new ClientGetter(nullCallback, uri, ctx, prio, this, new NullBucket(), null);
		core.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				get.cancel(null, core.clientContext);
			}
			
		}, timeout);
		try {
			core.clientContext.start(get);
		} catch (FetchException e) {
			// Ignore
		} catch (DatabaseDisabledException e) {
			// Impossible
		}
	}

	public boolean persistent() {
		return false;
	}

	public void removeFrom(ObjectContainer container) {
		throw new UnsupportedOperationException();
	}

}
