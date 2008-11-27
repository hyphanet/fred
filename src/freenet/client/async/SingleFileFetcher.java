/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;

import freenet.client.ArchiveContext;
import freenet.client.ArchiveExtractCallback;
import freenet.client.ArchiveFailureException;
import freenet.client.ArchiveManager;
import freenet.client.ArchiveRestartException;
import freenet.client.ArchiveStoreContext;
import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchContext;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestScheduler;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;

public class SingleFileFetcher extends SimpleSingleFileFetcher {

	private static boolean logMINOR;
	/** Original URI */
	final FreenetURI uri;
	/** Meta-strings. (Path elements that aren't part of a key type) */
	final LinkedList metaStrings;
	/** Number of metaStrings which were added by redirects etc. They are added to the start, so this is decremented
	 * when we consume one. */
	private int addedMetaStrings;
	final ClientMetadata clientMetadata;
	private Metadata metadata;
	private Metadata archiveMetadata;
	final ArchiveContext actx;
	/** Archive handler. We can only have one archive handler at a time. */
	private ArchiveStoreContext ah;
	private int recursionLevel;
	/** The URI of the currently-being-processed data, for archives etc. */
	private FreenetURI thisKey;
	private final LinkedList<COMPRESSOR_TYPE> decompressors;
	private final boolean dontTellClientGet;
	private final Bucket returnBucket;
	/** If true, success/failure is immediately reported to the client, and therefore we can check TOO_MANY_PATH_COMPONENTS. */
	private final boolean isFinal;
	private RequestScheduler sched;

	/** Create a new SingleFileFetcher and register self.
	 * Called when following a redirect, or direct from ClientGet.
	 * FIXME: Many times where this is called internally we might be better off using a copy constructor? 
	 */
	public SingleFileFetcher(ClientRequester parent, GetCompletionCallback cb, ClientMetadata metadata,
			ClientKey key, LinkedList metaStrings, FreenetURI origURI, int addedMetaStrings, FetchContext ctx,
			ArchiveContext actx, ArchiveStoreContext ah, Metadata archiveMetadata, int maxRetries, int recursionLevel,
			boolean dontTellClientGet, long l, boolean isEssential,
			Bucket returnBucket, boolean isFinal) throws FetchException {
		super(key, maxRetries, ctx, parent, cb, isEssential, false, l);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+key+" from "+origURI+" meta="+metaStrings.toString(), new Exception("debug"));
		this.isFinal = isFinal;
		this.cancelled = false;
		this.returnBucket = returnBucket;
		this.dontTellClientGet = dontTellClientGet;
		this.ah = ah;
		this.archiveMetadata = archiveMetadata;
		//this.uri = uri;
		//this.key = ClientKey.getBaseKey(uri);
		//metaStrings = uri.listMetaStrings();
		this.metaStrings = metaStrings;
		this.addedMetaStrings = addedMetaStrings;
		this.clientMetadata = metadata;
		thisKey = key.getURI();
		this.uri = origURI;
		this.actx = actx;
		this.recursionLevel = recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, "Too much recursion: "+recursionLevel+" > "+ctx.maxRecursionLevel);
		this.decompressors = new LinkedList<COMPRESSOR_TYPE>();
	}

	/** Copy constructor, modifies a few given fields, don't call schedule().
	 * Used for things like slave fetchers for MultiLevelMetadata, therefore does not remember returnBucket,
	 * metaStrings etc. */
	public SingleFileFetcher(SingleFileFetcher fetcher, Metadata newMeta, GetCompletionCallback callback, FetchContext ctx2) throws FetchException {
		// Don't add a block, we have already fetched the data, we are just handling the metadata in a different fetcher.
		super(fetcher.key, fetcher.maxRetries, ctx2, fetcher.parent, callback, false, true, fetcher.token);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+fetcher.key+" meta="+fetcher.metaStrings.toString(), new Exception("debug"));
		this.returnBucket = null;
		// We expect significant further processing in the parent
		this.isFinal = false;
		this.dontTellClientGet = fetcher.dontTellClientGet;
		this.actx = fetcher.actx;
		this.ah = fetcher.ah;
		this.archiveMetadata = fetcher.archiveMetadata;
		this.clientMetadata = (fetcher.clientMetadata != null ? (ClientMetadata) fetcher.clientMetadata.clone() : null);
		this.metadata = newMeta;
		this.metaStrings = new LinkedList();
		this.addedMetaStrings = 0;
		this.recursionLevel = fetcher.recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		this.thisKey = fetcher.thisKey;
		this.decompressors = fetcher.decompressors;
		this.uri = fetcher.uri;
	}

	// Process the completed data. May result in us going to a
	// splitfile, or another SingleFileFetcher, etc.
	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, RequestScheduler sched) {
		this.sched = sched;
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block);
		parent.completedBlock(fromStore);
		// Extract data
		
		if(block == null) {
			Logger.error(this, "block is null! fromStore="+fromStore+", token="+token, new Exception("error"));
			return;
		}
		Bucket data = extract(block, sched);
		if(data == null) {
			if(logMINOR)
				Logger.minor(this, "No data");
			// Already failed: if extract returns null it will call onFailure first.
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Block "+(block.isMetadata() ? "is metadata" : "is not metadata")+" on "+this);
		if(!block.isMetadata()) {
			onSuccess(new FetchResult(clientMetadata, data), sched);
		} else {
			if(!ctx.followRedirects) {
				onFailure(new FetchException(FetchException.INVALID_METADATA, "Told me not to follow redirects (splitfile block??)"), sched);
				data.free();
				return;
			}
			if(parent.isCancelled()) {
				onFailure(new FetchException(FetchException.CANCELLED), sched);
				data.free();
				return;
			}
			if(data.size() > ctx.maxMetadataSize) {
				onFailure(new FetchException(FetchException.TOO_BIG_METADATA), sched);
				data.free();
				return;
			}
			// Parse metadata
			try {
				metadata = Metadata.construct(data);
				wrapHandleMetadata(false);
				data.free();
			} catch (MetadataParseException e) {
				onFailure(new FetchException(FetchException.INVALID_METADATA, e), sched);
				data.free();
				return;
			} catch (IOException e) {
				// Bucket error?
				onFailure(new FetchException(FetchException.BUCKET_ERROR, e), sched);
				data.free();
				return;
			}
		}
	}

	@Override
	protected void onSuccess(FetchResult result, RequestScheduler sched) {
		this.sched = sched;
		unregister(false);
		if(parent.isCancelled()) {
			if(logMINOR)
				Logger.minor(this, "Parent is cancelled");
			result.asBucket().free();
			onFailure(new FetchException(FetchException.CANCELLED), sched);
			return;
		}
		if(!decompressors.isEmpty()) {
			Bucket data = result.asBucket();
			while(!decompressors.isEmpty()) {
				COMPRESSOR_TYPE c = decompressors.removeLast();
				try {
					long maxLen = Math.max(ctx.maxTempLength, ctx.maxOutputLength);
					if(logMINOR)
						Logger.minor(this, "Decompressing "+data+" size "+data.size()+" max length "+maxLen);
					data = c.decompress(data, ctx.bucketFactory, maxLen, maxLen * 4, decompressors.isEmpty() ? returnBucket : null);
					if(logMINOR)
						Logger.minor(this, "Decompressed to "+data+" size "+data.size());
				} catch (IOException e) {
					onFailure(new FetchException(FetchException.BUCKET_ERROR, e), sched);
					return;
				} catch (CompressionOutputSizeException e) {
					onFailure(new FetchException(FetchException.TOO_BIG, e.estimatedSize, (rcb == parent), result.getMimeType()), sched);
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

	/**
	 * Handle the current metadata. I.e. do something with it: transition to a splitfile, look up a manifest, etc.
	 * LOCKING: Synchronized as it changes so many variables; if we want to write the structure to disk, we don't
	 * want this running at the same time.
	 * @throws FetchException
	 * @throws MetadataParseException
	 * @throws ArchiveFailureException
	 * @throws ArchiveRestartException
	 */
	private synchronized void handleMetadata() throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		while(true) {
			if(metadata.isSimpleManifest()) {
				if(logMINOR) Logger.minor(this, "Is simple manifest");
				String name;
				if(metaStrings.isEmpty())
					throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, uri.addMetaStrings(new String[] { "" }));
				else name = removeMetaString();
				// Since metadata is a document, we just replace metadata here
				if(logMINOR) Logger.minor(this, "Next meta-string: "+name);
				if(name == null) {
					metadata = metadata.getDefaultDocument();
					if(metadata == null)
						throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, uri.addMetaStrings(new String[] { "" }));
				} else {
					metadata = metadata.getDocument(name);
					thisKey = thisKey.pushMetaString(name);
					if(metadata == null)
						throw new FetchException(FetchException.NOT_IN_ARCHIVE, "can't find "+name);
				}
				continue; // loop
			} else if(metadata.isArchiveManifest()) {
				if(logMINOR) Logger.minor(this, "Is archive manifest (type="+metadata.getArchiveType()+" codec="+metadata.getCompressionCodec()+')');
				if(metaStrings.isEmpty() && ctx.returnZIPManifests) {
					// Just return the archive, whole.
					metadata.setSimpleRedirect();
					continue;
				}
				// First we need the archive metadata.
				// Then parse it. Then we may need to fetch something from inside the archive.
				// It's more efficient to keep the existing ah if we can, and it is vital in
				// the case of binary blobs.
				if(ah == null || !ah.getKey().equals(thisKey))
					ah = (ArchiveStoreContext) ctx.archiveManager.makeHandler(thisKey, metadata.getArchiveType(), metadata.getCompressionCodec(), false, 
							(parent instanceof ClientGetter ? ((ClientGetter)parent).collectingBinaryBlob() : false));
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
					fetchArchive(false, archiveMetadata, ArchiveManager.METADATA_NAME, new ArchiveExtractCallback() {
						public void gotBucket(Bucket data) {
							try {
								metadata = Metadata.construct(data);
							} catch (MetadataParseException e) {
								// Invalid metadata
								onFailure(new FetchException(FetchException.INVALID_METADATA, e), sched);
								return;
							} catch (IOException e) {
								// Bucket error?
								onFailure(new FetchException(FetchException.BUCKET_ERROR, e), sched);
								return;
							}
							wrapHandleMetadata(true);
						}
						public void notInArchive() {
							onFailure(new FetchException(FetchException.INTERNAL_ERROR, "No metadata in container! Cannot happen as ArchiveManager should synthesise some!"), sched);
						}
					}); // will result in this function being called again
					return;
				}
				continue;
			} else if(metadata.isArchiveInternalRedirect()) {
				if(logMINOR) Logger.minor(this, "Is archive-internal redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata());
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime);
				if(metaStrings.isEmpty() && isFinal && clientMetadata.getMIMETypeNoParams() != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(clientMetadata.getMIMETypeNoParams())) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, -1, false, clientMetadata.getMIMEType());
				}
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive manifest");
				String filename = metadata.getZIPInternalName();
				if(logMINOR) Logger.minor(this, "Fetching "+filename);
				Bucket dataBucket = ah.get(filename, actx, null, recursionLevel+1, true);
				if(dataBucket != null) {
					if(logMINOR) Logger.minor(this, "Returning data");
					final Bucket out;
					try {
						// Data will not be freed until client is finished with it.
						if(returnBucket != null) {
							out = returnBucket;
							BucketTools.copy(dataBucket, out);
							dataBucket.free();
						} else {
							out = dataBucket;
						}
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					// Return the data
					ctx.executor.execute(new Runnable() {
						public void run() {
							onSuccess(new FetchResult(clientMetadata, out), sched);
						}
					}, "SingleFileFetcher onSuccess callback for "+this);
					
					return;
				} else {
					if(logMINOR) Logger.minor(this, "Fetching archive (thisKey="+thisKey+ ')');
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					fetchArchive(true, archiveMetadata, filename, new ArchiveExtractCallback() {
						public void gotBucket(Bucket data) {
							if(logMINOR) Logger.minor(this, "Returning data");
							Bucket out;
							try {
								// Data will not be freed until client is finished with it.
								if(returnBucket != null) {
									out = returnBucket;
									BucketTools.copy(data, out);
									data.free();
								} else {
									out = data;
								}
							} catch (IOException e) {
								onFailure(new FetchException(FetchException.BUCKET_ERROR), sched);
								return;
							}
							// Return the data
							onSuccess(new FetchResult(clientMetadata, out), sched);
						}
						public void notInArchive() {
							onFailure(new FetchException(FetchException.NOT_IN_ARCHIVE), sched);
						}
					});
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isMultiLevelMetadata()) {
				if(logMINOR) Logger.minor(this, "Is multi-level metadata");
				// Fetch on a second SingleFileFetcher, like with archives.
				metadata.setSimpleRedirect();
				final SingleFileFetcher f = new SingleFileFetcher(this, metadata, new MultiLevelMetadataCallback(), ctx);
				// Clear our own metadata so it can be garbage collected, it will be replaced by whatever is fetched.
				this.metadata = null;
				ctx.ticker.queueTimedJob(new Runnable() {
					public void run() {
						f.wrapHandleMetadata(true);
					}
				}, 0);
				return;
			} else if(metadata.isSingleFileRedirect()) {
				if(logMINOR) Logger.minor(this, "Is single-file redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime);

				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.ARCHIVE_TYPE.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(logMINOR) Logger.minor(this, "Handling implicit container... (redirect)");
					continue;
				}
				
				if(metaStrings.isEmpty() && isFinal && mimeType != null && ctx.allowedMIMETypes != null && 
						!ctx.allowedMIMETypes.contains(mimeType)) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, -1, false, clientMetadata.getMIMEType());
				}
				
				// Simple redirect
				// Just create a new SingleFileFetcher
				// Which will then fetch the target URI, and call the rcd.success
				// Hopefully!
				FreenetURI newURI = metadata.getSingleTarget();
				if(logMINOR) Logger.minor(this, "Redirecting to "+newURI);
				ClientKey redirectedKey;
				try {
					BaseClientKey k = BaseClientKey.getBaseKey(newURI);
					if(k instanceof ClientKey)
						redirectedKey = (ClientKey) k;
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

				final SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, redirectedKey, metaStrings, this.uri, addedMetaStrings, ctx, actx, ah, archiveMetadata, maxRetries, recursionLevel, false, token, true, returnBucket, isFinal);
				if((redirectedKey instanceof ClientCHK) && !((ClientCHK)redirectedKey).isMetadata())
					rcb.onBlockSetFinished(this);
				if(metadata.isCompressed()) {
					COMPRESSOR_TYPE codec = metadata.getCompressionCodec();
					f.addDecompressor(codec);
				}
				parent.onTransition(this, f);
				ctx.slowSerialExecutor[parent.priorityClass].execute(new Runnable() {
					public void run() {
						f.schedule();
					}
				}, "Schedule "+this);
				// All done! No longer our problem!
				return;
			} else if(metadata.isSplitfile()) {
				if(logMINOR) Logger.minor(this, "Fetching splitfile");
				
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime);
				
				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.ARCHIVE_TYPE.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(logMINOR) Logger.minor(this, "Handling implicit container... (splitfile)");
					continue;
				}
				
				if(metaStrings.isEmpty() && isFinal && mimeType != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(mimeType)) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, metadata.uncompressedDataLength(), false, clientMetadata.getMIMEType());
				}
				
				// Splitfile (possibly compressed)
				
				if(metadata.isCompressed()) {
					COMPRESSOR_TYPE codec = metadata.getCompressionCodec();
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
					
					throw new FetchException(FetchException.TOO_BIG, len, isFinal && decompressors.size() <= (metadata.isCompressed() ? 1 : 0), clientMetadata.getMIMEType());
				}
				
				SplitFileFetcher sf = new SplitFileFetcher(metadata, rcb, parent, ctx, 
						decompressors, clientMetadata, actx, recursionLevel, returnBucket, token);
				parent.onTransition(this, sf);
				sf.scheduleOffThread();
				rcb.onBlockSetFinished(this);
				// Clear our own metadata, we won't need it any more.
				// For multi-level metadata etc see above.
				metadata = null; 
				
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

	private void addDecompressor(COMPRESSOR_TYPE codec) {
		decompressors.addLast(codec);
	}

	private void fetchArchive(boolean forData, Metadata meta, String element, ArchiveExtractCallback callback) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		if(logMINOR) Logger.minor(this, "fetchArchive()");
		// Fetch the archive
		// How?
		// Spawn a separate SingleFileFetcher,
		// which fetches the archive, then calls
		// our Callback, which unpacks the archive, then
		// reschedules us.
		Metadata newMeta = (Metadata) meta.clone();
		newMeta.setSimpleRedirect();
		final SingleFileFetcher f;
		f = new SingleFileFetcher(this, newMeta, new ArchiveFetcherCallback(forData, element, callback), new FetchContext(ctx, FetchContext.SET_RETURN_ARCHIVES, true));
		if(logMINOR) Logger.minor(this, "fetchArchive(): "+f);
		ctx.executor.execute(new Runnable() {
			public void run() {
				// Fetch the archive. The archive fetcher callback will unpack it, and either call the element 
				// callback, or just go back around handleMetadata() on this, which will see that the data is now
				// available.
				f.wrapHandleMetadata(true);
			}
		}, "Fetching archive for "+this);
	}

	/**
	 * Call handleMetadata(), and deal with any resulting exceptions
	 */
	private void wrapHandleMetadata(boolean notFinalizedSize) {
		try {
			handleMetadata();
		} catch (MetadataParseException e) {
			onFailure(new FetchException(FetchException.INVALID_METADATA, e), sched);
		} catch (FetchException e) {
			if(notFinalizedSize)
				e.setNotFinalizedSize();
			onFailure(e, sched);
		} catch (ArchiveFailureException e) {
			onFailure(new FetchException(e), sched);
		} catch (ArchiveRestartException e) {
			onFailure(new FetchException(e), sched);
		}
	}
	
	class ArchiveFetcherCallback implements GetCompletionCallback {

		private final boolean wasFetchingFinalData;
		private final String element;
		private final ArchiveExtractCallback callback;
		
		ArchiveFetcherCallback(boolean wasFetchingFinalData, String element, ArchiveExtractCallback cb) {
			this.wasFetchingFinalData = wasFetchingFinalData;
			this.element = element;
			this.callback = cb;
		}
		
		public void onSuccess(FetchResult result, ClientGetState state) {
			try {
				ah.extractToCache(result.asBucket(), actx, element, callback);
			} catch (ArchiveFailureException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), sched);
				return;
			} catch (ArchiveRestartException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), sched);
				return;
			} finally {
				result.asBucket().free();
			}
			if(callback != null) return;
			wrapHandleMetadata(true);
		}

		public void onFailure(FetchException e, ClientGetState state) {
			// Force fatal as the fetcher is presumed to have made a reasonable effort.
			SingleFileFetcher.this.onFailure(e, true, sched);
		}

		public void onBlockSetFinished(ClientGetState state) {
			if(wasFetchingFinalData) {
				rcb.onBlockSetFinished(SingleFileFetcher.this);
			}
		}

		public void onTransition(ClientGetState oldState, ClientGetState newState) {
			// Ignore
		}

		public void onExpectedMIME(String mime) {
			// Ignore
		}

		public void onExpectedSize(long size) {
			rcb.onExpectedSize(size);
		}

		public void onFinalizedMetadata() {
			// Ignore
		}
		
	}

	class MultiLevelMetadataCallback implements GetCompletionCallback {
		
		public void onSuccess(FetchResult result, ClientGetState state) {
			try {
				metadata = Metadata.construct(result.asBucket());
			} catch (MetadataParseException e) {
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.INVALID_METADATA, e), sched);
				return;
			} catch (IOException e) {
				// Bucket error?
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), sched);
				return;
			} finally {
				result.asBucket().free();
			}
			wrapHandleMetadata(true);
		}
		
		public void onFailure(FetchException e, ClientGetState state) {
			// Pass it on; fetcher is assumed to have retried as appropriate already, so this is fatal.
			SingleFileFetcher.this.onFailure(e, true, sched);
		}

		public void onBlockSetFinished(ClientGetState state) {
			// Ignore as we are fetching metadata here
		}

		public void onTransition(ClientGetState oldState, ClientGetState newState) {
			// Ignore
		}

		public void onExpectedMIME(String mime) {
			// Ignore
		}

		public void onExpectedSize(long size) {
			rcb.onExpectedSize(size);
		}

		public void onFinalizedMetadata() {
			// Ignore
		}
		
	}
	
	@Override
	public boolean ignoreStore() {
		return ctx.ignoreStore;
	}

	/**
	 * Create a fetcher for a key.
	 */
	public static ClientGetState create(ClientRequester requester, GetCompletionCallback cb, 
			ClientMetadata clientMetadata, FreenetURI uri, FetchContext ctx, ArchiveContext actx, 
			int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, 
			Bucket returnBucket, boolean isFinal) throws MalformedURLException, FetchException {
		BaseClientKey key = BaseClientKey.getBaseKey(uri);
		if((clientMetadata == null || clientMetadata.isTrivial()) && (!uri.hasMetaStrings()) &&
				ctx.allowSplitfiles == false && ctx.followRedirects == false && 
				returnBucket == null && key instanceof ClientKey)
			return new SimpleSingleFileFetcher((ClientKey)key, maxRetries, ctx, requester, cb, isEssential, false, l);
		if(key instanceof ClientKey)
			return new SingleFileFetcher(requester, cb, clientMetadata, (ClientKey)key, uri.listMetaStrings(), uri, 0, ctx, actx, null, null, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, returnBucket, isFinal);
		else {
			return uskCreate(requester, cb, clientMetadata, (USK)key, uri.listMetaStrings(), ctx, actx, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, returnBucket, isFinal);
		}
	}

	private static ClientGetState uskCreate(ClientRequester requester, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetchContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, Bucket returnBucket, boolean isFinal) throws FetchException {
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
						new SingleFileFetcher(requester, myCB, clientMetadata, usk.getSSK(), metaStrings, 
								usk.getURI().addMetaStrings(metaStrings), 0, ctx, actx, null, null, maxRetries, recursionLevel, 
								dontTellClientGet, l, isEssential, returnBucket, isFinal);
					return sf;
				}
			} else {
				cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null);
				return null;
			}
		} else {
			// Do a thorough, blocking search
			USKFetcher fetcher =
				ctx.uskManager.getFetcher(usk.copy(-usk.suggestedEdition), ctx, requester, false);
			if(isEssential)
				requester.addMustSucceedBlocks(1);
			fetcher.addCallback(new MyUSKFetcherCallback(requester, cb, clientMetadata, usk, metaStrings, ctx, actx, maxRetries, recursionLevel, dontTellClientGet, l, returnBucket));
			return fetcher;
		}
	}

	public static class MyUSKFetcherCallback implements USKFetcherCallback {

		final ClientRequester parent;
		final GetCompletionCallback cb;
		final ClientMetadata clientMetadata;
		final USK usk;
		final LinkedList metaStrings;
		final FetchContext ctx;
		final ArchiveContext actx;
		final int maxRetries;
		final int recursionLevel;
		final boolean dontTellClientGet;
		final long token;
		final Bucket returnBucket;
		
		public MyUSKFetcherCallback(ClientRequester requester, GetCompletionCallback cb, ClientMetadata clientMetadata, USK usk, LinkedList metaStrings, FetchContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, Bucket returnBucket) {
			this.parent = requester;
			this.cb = cb;
			this.clientMetadata = clientMetadata;
			this.usk = usk;
			this.metaStrings = metaStrings;
			this.ctx = ctx;
			this.actx = actx;
			this.maxRetries = maxRetries;
			this.recursionLevel = recursionLevel;
			this.dontTellClientGet = dontTellClientGet;
			this.token = l;
			this.returnBucket = returnBucket;
		}

		public void onFoundEdition(long l, USK newUSK) {
			ClientSSK key = usk.getSSK(l);
			try {
				if(l == usk.suggestedEdition) {
					SingleFileFetcher sf = new SingleFileFetcher(parent, cb, clientMetadata, key, metaStrings, key.getURI().addMetaStrings(metaStrings),
							0, ctx, actx, null, null, maxRetries, recursionLevel+1, dontTellClientGet, token, false, returnBucket, true);
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

		public short getPollingPriorityNormal() {
			return parent.getPriorityClass();
		}

		public short getPollingPriorityProgress() {
			return parent.getPriorityClass();
		}

	}

}
