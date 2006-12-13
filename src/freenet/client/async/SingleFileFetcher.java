/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.KeyDecodeException;
import freenet.keys.USK;
import freenet.node.LowLevelGetException;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.BucketTools;

public class SingleFileFetcher extends BaseSingleFileFetcher implements ClientGetState {

	private static boolean logMINOR;
	/** Original URI */
	final FreenetURI uri;
	/** Meta-strings. (Path elements that aren't part of a key type) */
	final LinkedList metaStrings;
	/** Number of metaStrings which were added by redirects etc. They are added to the start, so this is decremented
	 * when we consume one. */
	private int addedMetaStrings;
	final GetCompletionCallback rcb;
	final ClientMetadata clientMetadata;
	private Metadata metadata;
	private Metadata archiveMetadata;
	final ArchiveContext actx;
	/** Archive handler. We can only have one archive handler at a time. */
	private ArchiveStoreContext ah;
	private int recursionLevel;
	/** The URI of the currently-being-processed data, for archives etc. */
	private FreenetURI thisKey;
	private final LinkedList decompressors;
	private final boolean dontTellClientGet;
	private Object token;
	private final Bucket returnBucket;
	/** If true, success/failure is immediately reported to the client, and therefore we can check TOO_MANY_PATH_COMPONENTS. */
	private final boolean isFinal;

	/** Create a new SingleFileFetcher and register self.
	 * Called when following a redirect, or direct from ClientGet.
	 * FIXME: Many times where this is called internally we might be better off using a copy constructor? 
	 */
	public SingleFileFetcher(BaseClientGetter get, GetCompletionCallback cb, ClientMetadata metadata,
			ClientKey key, LinkedList metaStrings, FreenetURI origURI, int addedMetaStrings, FetcherContext ctx,
			ArchiveContext actx, int maxRetries, int recursionLevel,
			boolean dontTellClientGet, Object token, boolean isEssential,
			Bucket returnBucket, boolean isFinal) throws FetchException {
		super(key, maxRetries, ctx, get);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+key+" from "+origURI+" meta="+metaStrings.toString(), new Exception("debug"));
		this.isFinal = isFinal;
		this.cancelled = false;
		this.returnBucket = returnBucket;
		this.dontTellClientGet = dontTellClientGet;
		this.token = token;
		//this.uri = uri;
		//this.key = ClientKey.getBaseKey(uri);
		//metaStrings = uri.listMetaStrings();
		this.metaStrings = metaStrings;
		this.addedMetaStrings = addedMetaStrings;
		this.rcb = cb;
		this.clientMetadata = metadata;
		thisKey = key.getURI();
		this.uri = origURI;
		this.actx = actx;
		this.recursionLevel = recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, "Too much recursion: "+recursionLevel+" > "+ctx.maxRecursionLevel);
		this.decompressors = new LinkedList();
		parent.addBlock();
		if(isEssential)
			parent.addMustSucceedBlocks(1);
	}

	/** Copy constructor, modifies a few given fields, don't call schedule().
	 * Used for things like slave fetchers for MultiLevelMetadata, therefore does not remember returnBucket,
	 * metaStrings etc. */
	public SingleFileFetcher(SingleFileFetcher fetcher, Metadata newMeta, GetCompletionCallback callback, FetcherContext ctx2) throws FetchException {
		super(fetcher.key, fetcher.maxRetries, ctx2, fetcher.parent);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+fetcher.key+" meta="+fetcher.metaStrings.toString(), new Exception("debug"));
		this.token = fetcher.token;
		this.returnBucket = null;
		// We expect significant further processing in the parent
		this.isFinal = false;
		this.dontTellClientGet = fetcher.dontTellClientGet;
		this.actx = fetcher.actx;
		this.ah = fetcher.ah;
		this.clientMetadata = (ClientMetadata) fetcher.clientMetadata.clone();
		this.metadata = newMeta;
		this.metaStrings = new LinkedList();
		this.addedMetaStrings = 0;
		this.rcb = callback;
		this.recursionLevel = fetcher.recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		this.thisKey = fetcher.thisKey;
		this.decompressors = fetcher.decompressors;
		this.uri = fetcher.uri;
	}

	// Process the completed data. May result in us going to a
	// splitfile, or another SingleFileFetcher, etc.
	public void onSuccess(ClientKeyBlock block, boolean fromStore) {
		parent.completedBlock(fromStore);
		// Extract data
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int)(Math.min(ctx.maxOutputLength, Integer.MAX_VALUE)), false);
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
					long maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
					data = c.decompress(data, ctx.bucketFactory, maxLen, maxLen * 4, decompressors.isEmpty() ? returnBucket : null);
				} catch (IOException e) {
					onFailure(new FetchException(FetchException.BUCKET_ERROR, e));
					return;
				} catch (CompressionOutputSizeException e) {
					onFailure(new FetchException(FetchException.TOO_BIG, e.estimatedSize, (rcb == parent), result.getMimeType()));
					return;
				}
			}
			result = new FetchResult(result, data);
		}
		if((!ctx.ignoreTooManyPathComponents) && (!metaStrings.isEmpty()) && isFinal) {
			// Some meta-strings left
			if(addedMetaStrings > 0) {
				// Should this be an error?
				// It would be useful to be able to fetch the data ...
				// On the other hand such inserts could cause unpredictable results?
				// Would be useful to make a redirect to the key we actually fetched.
				rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this);
			} else {
				// TOO_MANY_PATH_COMPONENTS
				// report to user
				if(logMINOR) {
					Logger.minor(this, "Too many path components: for "+uri+" meta="+metaStrings.toString());
				}
				FreenetURI tryURI = uri;
				tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
				rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, result.size(), (rcb == parent), result.getMimeType(), tryURI), this);
			}
			result.asBucket().free();
			return;
		} else if(result.size() > ctx.maxOutputLength) {
			rcb.onFailure(new FetchException(FetchException.TOO_BIG, result.size(), (rcb == parent), result.getMimeType()), this);
			result.asBucket().free();
		} else {
			rcb.onSuccess(result, this);
		}
	}

	private void handleMetadata() throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		while(true) {
			if(metadata.isSimpleManifest()) {
				if(logMINOR) Logger.minor(this, "Is simple manifest");
				String name;
				if(metaStrings.isEmpty())
					throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS);
				else name = removeMetaString();
				// Since metadata is a document, we just replace metadata here
				if(logMINOR) Logger.minor(this, "Next meta-string: "+name);
				if(name == null) {
					metadata = metadata.getDefaultDocument();
					if(metadata == null)
						throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS);
				} else {
					metadata = metadata.getDocument(name);
					thisKey = thisKey.pushMetaString(name);
					if(metadata == null)
						throw new FetchException(FetchException.NOT_IN_ARCHIVE);
				}
				continue; // loop
			} else if(metadata.isArchiveManifest()) {
				if(logMINOR) Logger.minor(this, "Is archive manifest");
				if(metaStrings.isEmpty() && ctx.returnZIPManifests) {
					// Just return the archive, whole.
					metadata.setSimpleRedirect();
					continue;
				}
				// First we need the archive metadata.
				// Then parse it.
				// Then we may need to fetch something from inside the archive.
				ah = (ArchiveStoreContext) ctx.archiveManager.makeHandler(thisKey, metadata.getArchiveType(), false);
				archiveMetadata = metadata;
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
					fetchArchive(false, archiveMetadata); // will result in this function being called again
					return;
				}
				continue;
			} else if(metadata.isArchiveInternalRedirect()) {
				if(logMINOR) Logger.minor(this, "Is archive-internal redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata());
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive manifest");
				String filename = metadata.getZIPInternalName();
				if(logMINOR) Logger.minor(this, "Fetching "+filename);
				Bucket dataBucket = ah.get(filename, actx, null, recursionLevel+1, true);
				if(dataBucket != null) {
					if(logMINOR) Logger.minor(this, "Returning data");
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
					if(logMINOR) Logger.minor(this, "Fetching archive (thisKey="+thisKey+ ')');
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					fetchArchive(true, archiveMetadata);
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isMultiLevelMetadata()) {
				if(logMINOR) Logger.minor(this, "Is multi-level metadata");
				// Fetch on a second SingleFileFetcher, like with archives.
				Metadata newMeta = (Metadata) metadata.clone();
				newMeta.setSimpleRedirect();
				SingleFileFetcher f = new SingleFileFetcher(this, newMeta, new MultiLevelMetadataCallback(), ctx);
				f.handleMetadata();
				return;
			} else if(metadata.isSingleFileRedirect()) {
				if(logMINOR) Logger.minor(this, "Is single-file redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				// FIXME implement implicit archive support
				
				// Simple redirect
				// Just create a new SingleFileFetcher
				// Which will then fetch the target URI, and call the rcd.success
				// Hopefully!
				FreenetURI newURI = metadata.getSingleTarget();
				if(logMINOR) Logger.minor(this, "Redirecting to "+newURI);
				ClientKey key;
				try {
					BaseClientKey k = BaseClientKey.getBaseKey(newURI);
					if(k instanceof ClientKey)
						key = (ClientKey) k;
					else
						// FIXME do we want to allow redirects to USKs?
						// Without redirects to USKs, all SSK and CHKs are static.
						// This may be a desirable property.
						throw new FetchException(FetchException.UNKNOWN_METADATA, "Redirect to a USK");
				} catch (MalformedURLException e) {
					throw new FetchException(FetchException.INVALID_URI, e);
				}
				LinkedList newMetaStrings = newURI.listMetaStrings();
				
				// Move any new meta strings to beginning of our list of remaining meta strings
				while(!newMetaStrings.isEmpty()) {
					Object o = newMetaStrings.removeLast();
					metaStrings.addFirst(o);
					addedMetaStrings++;
				}

				// **FIXME** Is key in the call to SingleFileFetcher here supposed to be this.key or the same key used in the try block above?  MultiLevelMetadataCallback.onSuccess() below uses this.key, thus the question
				SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, key, metaStrings, this.uri, addedMetaStrings, ctx, actx, maxRetries, recursionLevel, false, token, true, returnBucket, isFinal);
				if((key instanceof ClientCHK) && !((ClientCHK)key).isMetadata())
					rcb.onBlockSetFinished(this);
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					f.addDecompressor(codec);
				}
				parent.onTransition(this, f);
				f.schedule();
				// All done! No longer our problem!
				return;
			} else if(metadata.isSplitfile()) {
				if(logMINOR) Logger.minor(this, "Fetching splitfile");
				// FIXME implicit archive support
				
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				
				// Splitfile (possibly compressed)
				
				if(metadata.isCompressed()) {
					Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.getCompressionCodec());
					addDecompressor(codec);
				}
				
				if(isFinal && !ctx.ignoreTooManyPathComponents) {
					if(!metaStrings.isEmpty()) {
						// Some meta-strings left
						if(addedMetaStrings > 0) {
							// Should this be an error?
							// It would be useful to be able to fetch the data ...
							// On the other hand such inserts could cause unpredictable results?
							// Would be useful to make a redirect to the key we actually fetched.
							rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this);
						} else {
							// TOO_MANY_PATH_COMPONENTS
							// report to user
							FreenetURI tryURI = uri;
							tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
							rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, metadata.uncompressedDataLength(), (rcb == parent), clientMetadata.getMIMEType(), tryURI), this);
						}
						return;
					}
				} else
					if(logMINOR) Logger.minor(this, "Not finished: rcb="+rcb+" for "+this); 
				
				long len = metadata.dataLength();
				if(metadata.uncompressedDataLength() > len)
					len = metadata.uncompressedDataLength();
				
				if((len > ctx.maxOutputLength) ||
						(len > ctx.maxTempLength)) {
					
					onFailure(new FetchException(FetchException.TOO_BIG, len, isFinal && decompressors.size() <= (metadata.isCompressed() ? 1 : 0), clientMetadata.getMIMEType()));
					return;
				}
				
				SplitFileFetcher sf = new SplitFileFetcher(metadata, rcb, parent, ctx, 
						decompressors, clientMetadata, actx, recursionLevel, returnBucket, token);
				parent.onTransition(this, sf);
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

	private String removeMetaString() {
		String name = (String) metaStrings.removeFirst();
		if(addedMetaStrings > 0) addedMetaStrings--;
		return name;
	}

	private void addDecompressor(Compressor codec) {
		decompressors.addLast(codec);
	}

	private void fetchArchive(boolean forData, Metadata meta) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		if(logMINOR) Logger.minor(this, "fetchArchive()");
		// Fetch the archive
		// How?
		// Spawn a separate SingleFileFetcher,
		// which fetches the archive, then calls
		// our Callback, which unpacks the archive, then
		// reschedules us.
		Metadata newMeta = (Metadata) meta.clone();
		newMeta.setSimpleRedirect();
		SingleFileFetcher f;
		f = new SingleFileFetcher(this, newMeta, new ArchiveFetcherCallback(forData), new FetcherContext(ctx, FetcherContext.SET_RETURN_ARCHIVES, true));
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
			try {
				ah.extractToCache(result.asBucket(), actx);
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

		public void onTransition(ClientGetState oldState, ClientGetState newState) {
			// Ignore
		}
		
	}

	class MultiLevelMetadataCallback implements GetCompletionCallback {
		
		public void onSuccess(FetchResult result, ClientGetState state) {
			try {
				metadata = Metadata.construct(result.asBucket());
				handleMetadata();
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

		public void onTransition(ClientGetState oldState, ClientGetState newState) {
			// Ignore
		}
		
	}
	
	final void onFailure(FetchException e) {
		onFailure(e, false);
	}
	
	// Real onFailure
	protected void onFailure(FetchException e, boolean forceFatal) {
		if(parent.isCancelled() || cancelled) {
			if(logMINOR) Logger.minor(this, "Failing: cancelled");
			e = new FetchException(FetchException.CANCELLED);
			forceFatal = true;
		}
		if(!(e.isFatal() || forceFatal) ) {
			if(retry()) return;
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
		case LowLevelGetException.CANCELLED:
			onFailure(new FetchException(FetchException.CANCELLED));
			return;
		default:
			Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
			onFailure(new FetchException(FetchException.INTERNAL_ERROR));
			return;
		}
	}

	public void schedule() {
		super.schedule();
	}
	
	public Object getToken() {
		return token;
	}

	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	public static ClientGetState create(BaseClientGetter parent, GetCompletionCallback cb, ClientMetadata clientMetadata, FreenetURI uri, FetcherContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, Object token, boolean isEssential, Bucket returnBucket, boolean isFinal) throws MalformedURLException, FetchException {
		BaseClientKey key = BaseClientKey.getBaseKey(uri);
		if(key instanceof ClientKey)
			return new SingleFileFetcher(parent, cb, clientMetadata, (ClientKey)key, uri.listMetaStrings(), uri, 0, ctx, actx, maxRetries, recursionLevel, dontTellClientGet, token, isEssential, returnBucket, isFinal);
		else {
			return uskCreate(parent, cb, clientMetadata, (USK)key, uri.listMetaStrings(), ctx, actx, maxRetries, recursionLevel, dontTellClientGet, token, isEssential, returnBucket, isFinal);
		}
	}

	private static ClientGetState uskCreate(BaseClientGetter parent, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetcherContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, Object token, boolean isEssential, Bucket returnBucket, boolean isFinal) throws FetchException {
		if(usk.suggestedEdition >= 0) {
			// Return the latest known version but at least suggestedEdition.
			long edition = ctx.uskManager.lookup(usk);
			if(edition <= usk.suggestedEdition) {
				// Background fetch - start background fetch first so can pick up updates in the datastore during registration.
				ctx.uskManager.startTemporaryBackgroundFetcher(usk);
				edition = ctx.uskManager.lookup(usk);
				if(edition > usk.suggestedEdition) {
					if(logMINOR) Logger.minor(SingleFileFetcher.class, "Redirecting to edition "+edition);
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null);
					return null;
				} else {
					// Transition to SingleFileFetcher
					GetCompletionCallback myCB =
						new USKProxyCompletionCallback(usk, ctx.uskManager, cb);
					// Want to update the latest known good iff the fetch succeeds.
					SingleFileFetcher sf = 
						new SingleFileFetcher(parent, myCB, clientMetadata, usk.getSSK(), metaStrings, 
								usk.getURI().addMetaStrings(metaStrings), 0, ctx, actx, maxRetries, recursionLevel, 
								dontTellClientGet, token, false, returnBucket, isFinal);
					return sf;
				}
			} else {
				cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null);
				return null;
			}
		} else {
			// Do a thorough, blocking search
			USKFetcher fetcher =
				ctx.uskManager.getFetcher(usk.copy(-usk.suggestedEdition), ctx, parent, false);
			if(isEssential)
				parent.addMustSucceedBlocks(1);
			fetcher.addCallback(new MyUSKFetcherCallback(parent, cb, clientMetadata, usk, metaStrings, ctx, actx, maxRetries, recursionLevel, dontTellClientGet, token, returnBucket));
			return fetcher;
		}
	}

	public static class MyUSKFetcherCallback implements USKFetcherCallback {

		final BaseClientGetter parent;
		final GetCompletionCallback cb;
		final ClientMetadata clientMetadata;
		final USK usk;
		final LinkedList metaStrings;
		final FetcherContext ctx;
		final ArchiveContext actx;
		final int maxRetries;
		final int recursionLevel;
		final boolean dontTellClientGet;
		final Object token;
		final Bucket returnBucket;
		
		public MyUSKFetcherCallback(BaseClientGetter parent, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetcherContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, Object token, Bucket returnBucket) {
			this.parent = parent;
			this.cb = cb;
			this.clientMetadata = clientMetadata;
			this.usk = usk;
			this.metaStrings = metaStrings;
			this.ctx = ctx;
			this.actx = actx;
			this.maxRetries = maxRetries;
			this.recursionLevel = recursionLevel;
			this.dontTellClientGet = dontTellClientGet;
			this.token = token;
			this.returnBucket = returnBucket;
		}

		public void onFoundEdition(long l, USK newUSK) {
			ClientSSK key = usk.getSSK(l);
			try {
				if(l == usk.suggestedEdition) {
					SingleFileFetcher sf = new SingleFileFetcher(parent, cb, clientMetadata, key, metaStrings, key.getURI().addMetaStrings(metaStrings),
							0, ctx, actx, maxRetries, recursionLevel+1, dontTellClientGet, token, false, returnBucket, true);
					sf.schedule();
				} else {
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, newUSK.getURI().addMetaStrings(metaStrings)), null);
				}
			} catch (FetchException e) {
				cb.onFailure(e, null);
			}
		}

		public void onFailure() {
			cb.onFailure(new FetchException(FetchException.DATA_NOT_FOUND, "No USK found"), null);
		}

		public void onCancelled() {
			cb.onFailure(new FetchException(FetchException.CANCELLED, (String)null), null);
		}

	}

}
