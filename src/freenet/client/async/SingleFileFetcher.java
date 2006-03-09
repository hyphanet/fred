package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;

import freenet.client.ArchiveContext;
import freenet.client.ArchiveFailureException;
import freenet.client.ArchiveRestartException;
import freenet.client.ArchiveStoreContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.KeyDecodeException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

public class SingleFileFetcher extends ClientGetState implements SendableGet {

	final ClientGetter parent;
	//final FreenetURI uri;
	final ClientKey key;
	final LinkedList metaStrings;
	final FetcherContext ctx;
	final GetCompletionCallback rcb;
	final ClientMetadata clientMetadata;
	private Metadata metadata;
	final int maxRetries;
	final ArchiveContext actx;
	/** Archive handler. We can only have one archive handler at a time. */
	private ArchiveStoreContext ah;
	private int recursionLevel;
	/** The URI of the currently-being-processed data, for archives etc. */
	private FreenetURI thisKey;
	private int retryCount;
	private final LinkedList decompressors;
	private final boolean dontTellClientGet;
	private boolean cancelled;
	private Object token;
	private final Bucket returnBucket;
	
	/** Create a new SingleFileFetcher and register self.
	 * Called when following a redirect, or direct from ClientGet.
	 * @param token 
	 * @param dontTellClientGet 
	 */
	public SingleFileFetcher(ClientGetter get, GetCompletionCallback cb, ClientMetadata metadata, ClientKey key, LinkedList metaStrings, FetcherContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, Object token, boolean isEssential, Bucket returnBucket) throws FetchException {
		Logger.minor(this, "Creating SingleFileFetcher for "+key);
		this.cancelled = false;
		this.returnBucket = returnBucket;
		this.dontTellClientGet = dontTellClientGet;
		this.token = token;
		this.parent = get;
		//this.uri = uri;
		//this.key = ClientKey.getBaseKey(uri);
		//metaStrings = uri.listMetaStrings();
		this.key = key;
		this.metaStrings = metaStrings;
		this.ctx = ctx;
		retryCount = 0;
		this.rcb = cb;
		this.clientMetadata = metadata;
		this.maxRetries = maxRetries;
		thisKey = key.getURI();
		this.actx = actx;
		this.recursionLevel = recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, "Too much recursion: "+recursionLevel+" > "+ctx.maxRecursionLevel);
		this.decompressors = new LinkedList();
		parent.addBlock();
		if(isEssential)
			parent.addMustSucceedBlocks(1);
	}

	/** Called by ClientGet. */ 
	public SingleFileFetcher(ClientGetter get, GetCompletionCallback cb, ClientMetadata metadata, FreenetURI uri, FetcherContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, Object token, boolean isEssential, Bucket returnBucket) throws MalformedURLException, FetchException {
		this(get, cb, metadata, ClientKey.getBaseKey(uri), uri.listMetaStrings(), ctx, actx, maxRetries, recursionLevel, dontTellClientGet, token, isEssential, returnBucket);
	}
	
	/** Copy constructor, modifies a few given fields, don't call schedule().
	 * Used for things like slave fetchers for MultiLevelMetadata, therefore does not remember returnBucket. */
	public SingleFileFetcher(SingleFileFetcher fetcher, Metadata newMeta, GetCompletionCallback callback, FetcherContext ctx2) throws FetchException {
		Logger.minor(this, "Creating SingleFileFetcher for "+fetcher.key);
		this.token = fetcher.token;
		this.returnBucket = null;
		this.dontTellClientGet = fetcher.dontTellClientGet;
		this.actx = fetcher.actx;
		this.ah = fetcher.ah;
		this.clientMetadata = fetcher.clientMetadata;
		this.ctx = ctx2;
		this.key = fetcher.key;
		this.maxRetries = fetcher.maxRetries;
		this.metadata = newMeta;
		this.metaStrings = fetcher.metaStrings;
		this.parent = fetcher.parent;
		this.rcb = callback;
		this.retryCount = 0;
		this.recursionLevel = fetcher.recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		this.thisKey = fetcher.thisKey;
		this.decompressors = fetcher.decompressors;
	}

	public void schedule() {
		if(!dontTellClientGet)
			this.parent.currentState = this;
		if(key instanceof ClientCHK)
			parent.chkScheduler.register(this);
		else if(key instanceof ClientSSK)
			parent.sskScheduler.register(this);
		else
			throw new IllegalStateException(String.valueOf(key));
	}

	public ClientGetter getParent() {
		return parent;
	}

	public ClientKey getKey() {
		return key;
	}

	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	public int getRetryCount() {
		return retryCount;
	}

	// Process the completed data. May result in us going to a
	// splitfile, or another SingleFileFetcher, etc.
	public void onSuccess(ClientKeyBlock block, boolean fromStore) {
		parent.completedBlock(fromStore);
		// Extract data
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)));
		} catch (KeyDecodeException e1) {
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage()));
			return;
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
			return;
		}
		if(!block.isMetadata()) {
			onSuccess(new FetchResult(clientMetadata, data));
		} else {
			if(!ctx.followRedirects) {
				onFailure(new FetchException(FetchException.INVALID_METADATA, "Told me not to follow redirects (splitfile block??)"));
				return;
			}
			if(parent.isCancelled()) {
				onFailure(new FetchException(FetchException.CANCELLED));
				return;
			}
			if(data.size() > ctx.maxMetadataSize) {
				onFailure(new FetchException(FetchException.TOO_BIG_METADATA));
				return;
			}
			// Parse metadata
			try {
				metadata = Metadata.construct(data);
			} catch (MetadataParseException e) {
				onFailure(new FetchException(e));
				return;
			} catch (IOException e) {
				// Bucket error?
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
				return;
			}
			try {
				handleMetadata();
			} catch (MetadataParseException e) {
				onFailure(new FetchException(e));
				return;
			} catch (FetchException e) {
				onFailure(e);
				return;
			} catch (ArchiveFailureException e) {
				onFailure(new FetchException(e));
			} catch (ArchiveRestartException e) {
				onFailure(new FetchException(e));
			}
		}
	}

	private void onSuccess(FetchResult result) {
		if(!decompressors.isEmpty()) {
			Bucket data = result.asBucket();
			while(!decompressors.isEmpty()) {
				Compressor c = (Compressor) decompressors.removeLast();
				try {
					data = c.decompress(data, ctx.bucketFactory, Math.max(ctx.maxTempLength, ctx.maxOutputLength), decompressors.isEmpty() ? returnBucket : null);
				} catch (IOException e) {
					onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
					return;
				} catch (CompressionOutputSizeException e) {
					onFailure(new FetchException(FetchException.TOO_BIG, e));
					return;
				}
			}
			result = new FetchResult(result, data);
		}
		rcb.onSuccess(result, this);
	}

	private void handleMetadata() throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		while(true) {
			if(metadata.isSimpleManifest()) {
				String name;
				if(metaStrings.isEmpty())
					throw new FetchException(FetchException.NOT_ENOUGH_METASTRINGS);
				else
					name = (String) metaStrings.removeFirst();
				// Since metadata is a document, we just replace metadata here
				if(name == null) {
					metadata = metadata.getDefaultDocument();
					if(metadata == null)
						throw new FetchException(FetchException.NOT_ENOUGH_METASTRINGS);
				} else {
					metadata = metadata.getDocument(name);
					thisKey = thisKey.pushMetaString(name);
					if(metadata == null)
						throw new FetchException(FetchException.NOT_IN_ARCHIVE);
				}
				continue; // loop
			} else if(metadata.isArchiveManifest()) {
				if(metaStrings.isEmpty() && ctx.returnZIPManifests) {
					// Just return the archive, whole.
					metadata.setSimpleRedirect();
					continue;
				}
				// First we need the archive metadata.
				// Then parse it.
				// Then we may need to fetch something from inside the archive.
				ah = (ArchiveStoreContext) ctx.archiveManager.makeHandler(thisKey, metadata.getArchiveType(), false);
				// ah is set. This means we are currently handling an archive.
				Bucket metadataBucket;
				metadataBucket = ah.getMetadata(actx, null, recursionLevel+1, true);
				if(metadataBucket != null) {
					try {
						metadata = Metadata.construct(metadataBucket);
					} catch (IOException e) {
						// Bucket error?
						throw new FetchException(FetchException.BUCKET_ERROR, e);
					}
				} else {
					fetchArchive(false); // will result in this function being called again
					return;
				}
				continue;
			} else if(metadata.isArchiveInternalRedirect()) {
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive");
				if(metaStrings.isEmpty())
					throw new FetchException(FetchException.NOT_ENOUGH_METASTRINGS);
				Bucket dataBucket = ah.get((String) metaStrings.removeFirst(), actx, null, recursionLevel+1, true);
				if(dataBucket != null) {
					// The client may free it, which is bad, or it may hang on to it for so long that it gets
					// freed by us, which is also bad.
					// So copy it.
					// FIXME this is stupid, reconsider how we determine when to free buckets; refcounts maybe?
					Bucket out;
					try {
						if(returnBucket != null)
							out = returnBucket;
						else
							out = ctx.bucketFactory.makeBucket(dataBucket.size());
						BucketTools.copy(dataBucket, out);
					} catch (IOException e) {
						onFailure(new FetchException(FetchException.BUCKET_ERROR));
						return;
					}
					// Return the data
					onSuccess(new FetchResult(this.clientMetadata, out));
					return;
				} else {
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					fetchArchive(true);
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isMultiLevelMetadata()) {
				// Fetch on a second SingleFileFetcher, like with archives.
				Metadata newMeta = (Metadata) metadata.clone();
				newMeta.setSimpleRedirect();
				SingleFileFetcher f = new SingleFileFetcher(this, newMeta, new MultiLevelMetadataCallback(), ctx);
				f.handleMetadata();
				return;
			} else if(metadata.isSingleFileRedirect()) {
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				// FIXME implement implicit archive support
				
				// Simple redirect
				// Just create a new SingleFileFetcher
				// Which will then fetch the target URI, and call the rcd.success
				// Hopefully!
				FreenetURI uri = metadata.getSingleTarget();
				Logger.minor(this, "Redirecting to "+uri);
				ClientKey key;
				try {
					key = ClientKey.getBaseKey(uri);
				} catch (MalformedURLException e) {
					throw new FetchException(FetchException.INVALID_URI, e);
				}
				if(key instanceof ClientCHK && !((ClientCHK)key).isMetadata())
					rcb.onBlockSetFinished(this);
				LinkedList newMetaStrings = uri.listMetaStrings();
				
				// Move any new meta strings to beginning of our list of remaining meta strings
				while(!newMetaStrings.isEmpty()) {
					Object o = newMetaStrings.removeLast();
					metaStrings.addFirst(o);
				}

				SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, key, metaStrings, ctx, actx, maxRetries, recursionLevel, false, null, true, returnBucket);
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					f.addDecompressor(codec);
				}
				f.schedule();
				// All done! No longer our problem!
				return;
			} else if(metadata.isSplitfile()) {
				Logger.minor(this, "Fetching splitfile");
				// FIXME implicit archive support
				
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				
				// Splitfile (possibly compressed)
				
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					addDecompressor(codec);
				}
				
				SplitFileFetcher sf = new SplitFileFetcher(metadata, rcb, parent, ctx, 
						decompressors, clientMetadata, actx, recursionLevel, returnBucket);
				sf.schedule();
				rcb.onBlockSetFinished(this);
				// SplitFile will now run.
				// Then it will return data to rcd.
				// We are now out of the loop. Yay!
				return;
			} else {
				Logger.error(this, "Don't know what to do with metadata: "+metadata);
				throw new FetchException(FetchException.UNKNOWN_METADATA);
			}
		}
	}

	private void addDecompressor(Compressor codec) {
		decompressors.addLast(codec);
	}

	private void fetchArchive(boolean forData) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		// Fetch the archive
		// How?
		// Spawn a separate SingleFileFetcher,
		// which fetches the archive, then calls
		// our Callback, which unpacks the archive, then
		// reschedules us.
		Metadata newMeta = (Metadata) metadata.clone();
		newMeta.setSimpleRedirect();
		SingleFileFetcher f;
		f = new SingleFileFetcher(this, newMeta, new ArchiveFetcherCallback(forData), new FetcherContext(ctx, FetcherContext.SET_RETURN_ARCHIVES));
		f.handleMetadata();
		// When it is done (if successful), the ArchiveCallback will re-call this function.
		// Which will then discover that the metadata *is* available.
		// And will also discover that the data is available, and will complete.
	}

	class ArchiveFetcherCallback implements GetCompletionCallback {

		private final boolean wasFetchingFinalData;
		
		ArchiveFetcherCallback(boolean wasFetchingFinalData) {
			this.wasFetchingFinalData = wasFetchingFinalData;
		}
		
		public void onSuccess(FetchResult result, ClientGetState state) {
			parent.currentState = SingleFileFetcher.this;
			try {
				ctx.archiveManager.extractToCache(thisKey, ah.getArchiveType(), result.asBucket(), actx, ah);
			} catch (ArchiveFailureException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
			} catch (ArchiveRestartException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
			}
			try {
				handleMetadata();
			} catch (MetadataParseException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
			} catch (FetchException e) {
				SingleFileFetcher.this.onFailure(e);
			} catch (ArchiveFailureException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
			} catch (ArchiveRestartException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
			}
		}

		public void onFailure(FetchException e, ClientGetState state) {
			// Force fatal as the fetcher is presumed to have made a reasonable effort.
			SingleFileFetcher.this.onFailure(e, true);
		}

		public void onBlockSetFinished(ClientGetState state) {
			if(wasFetchingFinalData) {
				rcb.onBlockSetFinished(SingleFileFetcher.this);
			}
		}
		
	}

	class MultiLevelMetadataCallback implements GetCompletionCallback {
		
		public void onSuccess(FetchResult result, ClientGetState state) {
			parent.currentState = SingleFileFetcher.this;
			try {
				metadata = Metadata.construct(result.asBucket());
				SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, key, metaStrings, ctx, actx, maxRetries, recursionLevel, dontTellClientGet, null, true, returnBucket);
				f.metadata = metadata;
				f.handleMetadata();
			} catch (MetadataParseException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e));
				return;
			} catch (IOException e) {
				// Bucket error?
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
				return;
			} catch (FetchException e) {
				onFailure(e, SingleFileFetcher.this);
			} catch (ArchiveFailureException e) {
				onFailure(new FetchException(FetchException.ARCHIVE_FAILURE), SingleFileFetcher.this);
			} catch (ArchiveRestartException e) {
				onFailure(new FetchException(FetchException.ARCHIVE_RESTART), SingleFileFetcher.this);
			}
		}
		
		public void onFailure(FetchException e, ClientGetState state) {
			// Pass it on; fetcher is assumed to have retried as appropriate already, so this is fatal.
			SingleFileFetcher.this.onFailure(e, true);
		}

		public void onBlockSetFinished(ClientGetState state) {
			// Ignore as we are fetching metadata here
		}
		
	}
	
	private final void onFailure(FetchException e) {
		onFailure(e, false);
	}
	
	// Real onFailure
	private void onFailure(FetchException e, boolean forceFatal) {
		if(parent.isCancelled() || cancelled) {
			e = new FetchException(FetchException.CANCELLED);
		}
		if(!(e.isFatal() || forceFatal) ) {
			if((retryCount <= maxRetries) && maxRetries != -1) {
				if(parent.isCancelled()) {
					onFailure(new FetchException(FetchException.CANCELLED));
					return;
				}
				retryCount++;
				schedule();
				return;
			}
		}
		// :(
		if(e.isFatal() || forceFatal)
			parent.fatallyFailedBlock();
		else
			parent.failedBlock();
		rcb.onFailure(e, this);
	}

	// Translate it, then call the real onFailure
	public void onFailure(LowLevelGetException e) {
		switch(e.code) {
		case LowLevelGetException.DATA_NOT_FOUND:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND));
			return;
		case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
			onFailure(new FetchException(FetchException.DATA_NOT_FOUND));
			return;
		case LowLevelGetException.DECODE_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR));
			return;
		case LowLevelGetException.INTERNAL_ERROR:
			onFailure(new FetchException(FetchException.INTERNAL_ERROR));
			return;
		case LowLevelGetException.REJECTED_OVERLOAD:
			onFailure(new FetchException(FetchException.REJECTED_OVERLOAD));
			return;
		case LowLevelGetException.ROUTE_NOT_FOUND:
			onFailure(new FetchException(FetchException.ROUTE_NOT_FOUND));
			return;
		case LowLevelGetException.TRANSFER_FAILED:
			onFailure(new FetchException(FetchException.TRANSFER_FAILED));
			return;
		case LowLevelGetException.VERIFY_FAILED:
			onFailure(new FetchException(FetchException.BLOCK_DECODE_ERROR));
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR));
			return;
		}
	}

	public Object getToken() {
		return token;
	}

	public synchronized void cancel() {
		cancelled = true;
	}

	public boolean isFinished() {
		return cancelled;
	}

	/** Do the request, blocking. Called by RequestStarter. */
	public void send(Node node) {
		if(cancelled) {
			onFailure(new FetchException(FetchException.CANCELLED), false);
			return;
		}
		// Do we need to support the last 3?
		ClientKeyBlock block;
		try {
			block = node.realGetKey(key, ctx.localRequestOnly, ctx.cacheLocalRequests, ctx.ignoreStore);
		} catch (LowLevelGetException e) {
			onFailure(e);
			return;
		} catch (Throwable t) {
			onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR));
			return;
		}
		onSuccess(block, false);
	}

	public Object getClient() {
		return parent.getClient();
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public ClientRequest getClientRequest() {
		return parent;
	}

}
