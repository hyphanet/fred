/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.IOException;
import java.util.HashMap;

import freenet.client.async.BackgroundBlockEncoder;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutter;
import freenet.client.async.HealingQueue;
import freenet.client.async.SimpleManifestPutter;
import freenet.client.events.ClientEventListener;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.EventLogger;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.NullPersistentFileTracker;
import freenet.support.io.PersistentFileTracker;

public class HighLevelSimpleClientImpl implements HighLevelSimpleClient {

	private final ArchiveManager archiveManager;
	private final short priorityClass;
	private final BucketFactory bucketFactory;
	private final BucketFactory persistentBucketFactory;
	private final PersistentFileTracker persistentFileTracker;
	private final NodeClientCore core;
	private final BackgroundBlockEncoder blockEncoder;
	/** One CEP for all requests and inserts */
	private final ClientEventProducer globalEventProducer;
	private long curMaxLength;
	private long curMaxTempLength;
	private int curMaxMetadataLength;
	private final RandomSource random;
	private final HealingQueue healingQueue;
	/** See comments in Node */
	private final boolean cacheLocalRequests;
	private final boolean forceDontIgnoreTooManyPathComponents;
	static final int MAX_RECURSION = 10;
	static final int MAX_ARCHIVE_RESTARTS = 2;
	static final int MAX_ARCHIVE_LEVELS = 4;
	static final boolean DONT_ENTER_IMPLICIT_ARCHIVES = true;
	/** Number of threads used by a splitfile fetch */
	static final int SPLITFILE_THREADS = 20;
	/** Number of retries allowed per block in a splitfile. Must be at least 1 as 
	 * on the first try we just check the datastore.
	 */
	static final int SPLITFILE_BLOCK_RETRIES = 3;
	/** Number of retries allowed on non-splitfile fetches. Unlike above, we always
	 * go to network. */
	static final int NON_SPLITFILE_RETRIES = 2;
	/** Whether to fetch splitfiles. Don't turn this off! */
	static final boolean FETCH_SPLITFILES = true;
	/** Whether to follow redirects etc. If false, we only fetch a plain block of data. 
	 * Don't turn this off either! */
	static final boolean FOLLOW_REDIRECTS = true;
	/** If set, only check the local datastore, don't send an actual request out.
	 * Don't turn this off either. */
	static final boolean LOCAL_REQUESTS_ONLY = false;
	static final int SPLITFILE_INSERT_THREADS = 20;
	/** Number of retries on inserts */
	static final int INSERT_RETRIES = 10;
	/** Number of RNFs on insert that make a success, or -1 on large networks */
	static final int CONSECUTIVE_RNFS_ASSUME_SUCCESS = 2;
	// going by memory usage only; 4kB per stripe
	static final int MAX_SPLITFILE_BLOCKS_PER_SEGMENT = 1024;
	static final int MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 1536;
	static final int SPLITFILE_BLOCKS_PER_SEGMENT = 128;
	static final int SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 64;
	
	
	public HighLevelSimpleClientImpl(NodeClientCore node, ArchiveManager mgr, BucketFactory bf, RandomSource r, boolean cacheLocalRequests, short priorityClass, boolean forceDontIgnoreTooManyPathComponents) {
		this.core = node;
		archiveManager = mgr;
		this.priorityClass = priorityClass;
		bucketFactory = bf;
		this.persistentFileTracker = node.persistentTempBucketFactory;
		random = r;
		this.globalEventProducer = new SimpleEventProducer();
		globalEventProducer.addEventListener(new EventLogger(Logger.MINOR));
		curMaxLength = Long.MAX_VALUE;
		curMaxTempLength = Long.MAX_VALUE;
		curMaxMetadataLength = 1024 * 1024;
		this.cacheLocalRequests = cacheLocalRequests;
		this.persistentBucketFactory = node.persistentEncryptedTempBucketFactory;
		this.healingQueue = node.getHealingQueue();
		this.blockEncoder = node.backgroundBlockEncoder;
		this.forceDontIgnoreTooManyPathComponents = forceDontIgnoreTooManyPathComponents;
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
		ClientGetter get = new ClientGetter(fw, core.requestStarters.chkFetchScheduler, core.requestStarters.sskFetchScheduler, uri, context, priorityClass, this, null, null);
		get.start();
		return fw.waitForCompletion();
	}

	public FetchResult fetch(FreenetURI uri, long overrideMaxSize) throws FetchException {
		return fetch(uri, overrideMaxSize, this);
	}

	public FetchResult fetch(FreenetURI uri, long overrideMaxSize, Object clientContext) throws FetchException {
		if(uri == null) throw new NullPointerException();
		FetchWaiter fw = new FetchWaiter();
		FetchContext context = getFetchContext(overrideMaxSize);
		ClientGetter get = new ClientGetter(fw, core.requestStarters.chkFetchScheduler, core.requestStarters.sskFetchScheduler, uri, context, priorityClass, clientContext, null, null);
		get.start();
		return fw.waitForCompletion();
	}
	
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, String filenameHint) throws InsertException {
		return insert(insert, getCHKOnly, filenameHint, false);
	}
	
	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, String filenameHint, boolean isMetadata) throws InsertException {
		InsertContext context = getInsertContext(true);
		PutWaiter pw = new PutWaiter();
		ClientPutter put = new ClientPutter(pw, insert.getData(), insert.desiredURI, insert.clientMetadata, 
				context, core.requestStarters.chkPutScheduler, core.requestStarters.sskPutScheduler, priorityClass, 
				getCHKOnly, isMetadata, this, null, filenameHint, false);
		put.start(false);
		return pw.waitForCompletion();
	}

	public FreenetURI insertRedirect(FreenetURI insertURI, FreenetURI targetURI) throws InsertException {
		Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, new ClientMetadata());
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
			new SimpleManifestPutter(pw, core.requestStarters.chkPutScheduler, core.requestStarters.sskPutScheduler, SimpleManifestPutter.bucketsByNameToManifestEntries(bucketsByName), priorityClass, insertURI, defaultName, getInsertContext(true), false, this, false);
		putter.start();
		return pw.waitForCompletion();
	}
	
	public void addGlobalHook(ClientEventListener listener) {
		globalEventProducer.addEventListener(listener);
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
				SPLITFILE_THREADS, SPLITFILE_BLOCK_RETRIES, NON_SPLITFILE_RETRIES,
				FETCH_SPLITFILES, FOLLOW_REDIRECTS, LOCAL_REQUESTS_ONLY,
				MAX_SPLITFILE_BLOCKS_PER_SEGMENT, MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT,
				random, archiveManager, bucketFactory, globalEventProducer, 
				cacheLocalRequests, core.uskManager, healingQueue, 
				forceDontIgnoreTooManyPathComponents ? false : core.ignoreTooManyPathComponents, core.getTicker(), core.getExecutor());
	}

	public InsertContext getInsertContext(boolean forceNonPersistent) {
		return new InsertContext(bucketFactory, forceNonPersistent ? bucketFactory : persistentBucketFactory,
				forceNonPersistent ? new NullPersistentFileTracker() : persistentFileTracker,
				random, INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_INSERT_THREADS, SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, 
				globalEventProducer, cacheLocalRequests, core.uskManager, blockEncoder, core.getExecutor());
	}
}
