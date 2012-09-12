/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveContext;
import freenet.client.ArchiveExtractCallback;
import freenet.client.ArchiveFailureException;
import freenet.client.ArchiveHandler;
import freenet.client.ArchiveManager;
import freenet.client.ArchiveRestartException;
import freenet.client.ClientMetadata;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.crypt.HashResult;
import freenet.crypt.MultiHashInputStream;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressorThreadManager;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

public class SingleFileFetcher extends SimpleSingleFileFetcher {

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/** Original URI */
	final FreenetURI uri;
	/** Meta-strings. (Path elements that aren't part of a key type) */
	private final ArrayList<String> metaStrings;
	/** Number of metaStrings which were added by redirects etc. They are added to the start, so this is decremented
	 * when we consume one. */
	private int addedMetaStrings;
	final ClientMetadata clientMetadata;
	private Metadata metadata;
	private Metadata archiveMetadata;
	final ArchiveContext actx;
	/** Archive handler. We can only have one archive handler at a time. */
	private ArchiveHandler ah;
	private int recursionLevel;
	/** The URI of the currently-being-processed data, for archives etc. */
	private FreenetURI thisKey;
	private final LinkedList<COMPRESSOR_TYPE> decompressors;
	private final boolean dontTellClientGet;
	/** If true, success/failure is immediately reported to the client, and therefore we can check TOO_MANY_PATH_COMPONENTS. */
	private final boolean isFinal;
	private final SnoopMetadata metaSnoop;
	private final SnoopBucket bucketSnoop;

	/** Create a new SingleFileFetcher and register self.
	 * Called when following a redirect, or direct from ClientGet.
	 * FIXME: Many times where this is called internally we might be better off using a copy constructor? 
	 * @param topCompatibilityMode 
	 * @param topDontCompress 
	 * @param hasInitialMetadata 
	 */
	public SingleFileFetcher(ClientRequester parent, GetCompletionCallback cb, ClientMetadata metadata,
			ClientKey key, List<String> metaStrings, FreenetURI origURI, int addedMetaStrings, FetchContext ctx, boolean deleteFetchContext, boolean realTimeFlag,
			ArchiveContext actx, ArchiveHandler ah, Metadata archiveMetadata, int maxRetries, int recursionLevel,
			boolean dontTellClientGet, long l, boolean isEssential,
			boolean isFinal, boolean topDontCompress, short topCompatibilityMode, ObjectContainer container, ClientContext context, boolean hasInitialMetadata) throws FetchException {
		super(key, maxRetries, ctx, parent, cb, isEssential, false, l, container, context, deleteFetchContext, realTimeFlag);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+key+" from "+origURI+" meta="+metaStrings.toString()+" persistent="+persistent, new Exception("debug"));
		this.isFinal = isFinal;
		this.cancelled = false;
		this.dontTellClientGet = dontTellClientGet;
		if(persistent && ah != null) ah = ah.cloneHandler();
		this.ah = ah;
		this.archiveMetadata = archiveMetadata;
		//this.uri = uri;
		//this.key = ClientKey.getBaseKey(uri);
		//metaStrings = uri.listMetaStrings();
		if(metaStrings instanceof ArrayList && !persistent)
			this.metaStrings = (ArrayList<String>)metaStrings;
		else
			// Always copy if persistent
			this.metaStrings = new ArrayList<String>(metaStrings);
		this.addedMetaStrings = addedMetaStrings;
		this.clientMetadata = (metadata != null ? metadata.clone() : new ClientMetadata());
		if(hasInitialMetadata)
			thisKey = FreenetURI.EMPTY_CHK_URI.clone();
		else
			thisKey = key.getURI();
		if(origURI == null) throw new NullPointerException();
		this.uri = persistent ? origURI.clone() : origURI;
		this.actx = actx;
		this.recursionLevel = recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, "Too much recursion: "+recursionLevel+" > "+ctx.maxRecursionLevel);
		this.decompressors = new LinkedList<COMPRESSOR_TYPE>();
		this.topDontCompress = topDontCompress;
		this.topCompatibilityMode = topCompatibilityMode;
		if(parent instanceof ClientGetter) {
			metaSnoop = ((ClientGetter)parent).getMetaSnoop();
			bucketSnoop = ((ClientGetter)parent).getBucketSnoop();
		}
		else {
			metaSnoop = null;
			bucketSnoop = null;
		}
	}

	/** Copy constructor, modifies a few given fields, don't call schedule().
	 * Used for things like slave fetchers for MultiLevelMetadata, therefore does not remember returnBucket,
	 * metaStrings etc. */
	public SingleFileFetcher(SingleFileFetcher fetcher, boolean persistent, boolean deleteFetchContext, Metadata newMeta, GetCompletionCallback callback, FetchContext ctx2, ObjectContainer container, ClientContext context) throws FetchException {
		// Don't add a block, we have already fetched the data, we are just handling the metadata in a different fetcher.
		super(persistent ? fetcher.key.cloneKey() : fetcher.key, fetcher.maxRetries, ctx2, fetcher.parent, callback, false, true, fetcher.token, container, context, deleteFetchContext, fetcher.realTimeFlag);
		if(logMINOR) Logger.minor(this, "Creating SingleFileFetcher for "+fetcher.key+" meta="+fetcher.metaStrings.toString(), new Exception("debug"));
		// We expect significant further processing in the parent
		this.isFinal = false;
		this.dontTellClientGet = fetcher.dontTellClientGet;
		this.actx = fetcher.actx;
		this.ah = fetcher.ah;
		if(persistent && ah != null) ah = ah.cloneHandler();
		this.archiveMetadata = null;
		this.clientMetadata = (fetcher.clientMetadata != null ? fetcher.clientMetadata.clone() : new ClientMetadata());
		this.metadata = newMeta;
		this.metaStrings = new ArrayList<String>();
		this.addedMetaStrings = 0;
		this.recursionLevel = fetcher.recursionLevel + 1;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		this.thisKey = persistent ? fetcher.thisKey.clone() : fetcher.thisKey;
		// Do not copy the decompressors. Whether the metadata/container is compressed
		// is independant of whether the final data is; when we find the data we will
		// call back into the original fetcher.
		this.decompressors = new LinkedList<COMPRESSOR_TYPE>();
		if(fetcher.uri == null) throw new NullPointerException();
		this.uri = persistent ? fetcher.uri.clone() : fetcher.uri;
		this.metaSnoop = fetcher.metaSnoop;
		this.bucketSnoop = fetcher.bucketSnoop;
		this.topDontCompress = fetcher.topDontCompress;
		this.topCompatibilityMode = fetcher.topCompatibilityMode;
	}

	// Process the completed data. May result in us going to a
	// splitfile, or another SingleFileFetcher, etc.
	@Override
	public void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(parent, 1);
			container.activate(ctx, 1);
		}
		if(parent instanceof ClientGetter)
			((ClientGetter)parent).addKeyToBinaryBlob(block, container, context);
		parent.completedBlock(fromStore, container, context);
		// Extract data
		
		if(block == null) {
			Logger.error(this, "block is null! fromStore="+fromStore+", token="+token, new Exception("error"));
			return;
		}
		Bucket data = extract(block, container, context);
		if(key instanceof ClientSSK) {
			context.uskManager.checkUSK(uri, persistent, container, data != null && !block.isMetadata());
		}
		if(data == null) {
			if(logMINOR)
				Logger.minor(this, "No data");
			// Already failed: if extract returns null it will call onFailure first.
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Block "+(block.isMetadata() ? "is metadata" : "is not metadata")+" on "+this);

		if(bucketSnoop != null) {
			if(persistent)
				container.activate(bucketSnoop, 1);
			if(bucketSnoop.snoopBucket(data, block.isMetadata(), container, context)) {
				cancel(container, context);
				if(persistent)
					container.deactivate(bucketSnoop, 1);
				data.free();
				return;
			}
			if(persistent)
				container.deactivate(bucketSnoop, 1);
		}

		if(!block.isMetadata()) {
			onSuccess(new FetchResult(clientMetadata, data), container, context);
		} else {
			handleMetadata(data, container, context);
		}
	}

	// Package-local so that ClientGetter can call it instead of schedule().
	void startWithMetadata(Bucket data, ObjectContainer container, ClientContext context) {
		parent.completedBlock(true, container, context);
		handleMetadata(data, container, context);
	}
	
	private void handleMetadata(Bucket data, ObjectContainer container, ClientContext context) {
		if(!ctx.followRedirects) {
			onFailure(new FetchException(FetchException.INVALID_METADATA, "Told me not to follow redirects (splitfile block??)"), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		}
		if(parent.isCancelled()) {
			onFailure(new FetchException(FetchException.CANCELLED), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		}
		if(data.size() > ctx.maxMetadataSize) {
			onFailure(new FetchException(FetchException.TOO_BIG_METADATA), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		}
		// Parse metadata
		try {
			removeMetadata(container);
			metadata = Metadata.construct(data);
			if(persistent)
				container.store(this);
			innerWrapHandleMetadata(false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
		} catch (MetadataParseException e) {
			onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		} catch (EOFException e) {
			// This is a metadata error too.
			onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		} catch (IOException e) {
			// Bucket error?
			onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, container, context);
			data.free();
			if(persistent) data.removeFrom(container);
			return;
		}
	}
	
	@Override
	protected void onSuccess(FetchResult result, ObjectContainer container, ClientContext context) {
		if(persistent) {
			container.activate(decompressors, 1);
			container.activate(parent, 1);
			container.activate(ctx, 1);
			container.activate(rcb, 1);
		}
		removeMetadata(container);
		removeArchiveMetadata(container);
		synchronized(this) {
			// So a SingleKeyListener isn't created.
			finished = true;
		}
		if(parent.isCancelled()) {
			if(logMINOR)
				Logger.minor(this, "Parent is cancelled");
			result.asBucket().free();
			if(persistent) result.asBucket().removeFrom(container);
			onFailure(new FetchException(FetchException.CANCELLED), false, container, context);
			return;
		}
		if((!ctx.ignoreTooManyPathComponents) && (!metaStrings.isEmpty()) && isFinal) {
			// Some meta-strings left
			if(addedMetaStrings > 0) {
				// Should this be an error?
				// It would be useful to be able to fetch the data ...
				// On the other hand such inserts could cause unpredictable results?
				// Would be useful to make a redirect to the key we actually fetched.
				rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this, container, context);
			} else {
				// TOO_MANY_PATH_COMPONENTS
				// report to user
				if(logMINOR) {
					Logger.minor(this, "Too many path components: for "+uri+" meta="+metaStrings.toString());
				}
				FreenetURI tryURI = uri;
				tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
				rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, result.size(), (rcb == parent), result.getMimeType(), tryURI), this, container, context);
			}
			result.asBucket().free();
			if(persistent) result.asBucket().removeFrom(container);
			return;
		} else if(result.size() > ctx.maxOutputLength) {
			rcb.onFailure(new FetchException(FetchException.TOO_BIG, result.size(), (rcb == parent), result.getMimeType()), this, container, context);
			result.asBucket().free();
			if(persistent) result.asBucket().removeFrom(container);
		} else {
			rcb.onSuccess(new SingleFileStreamGenerator(result.asBucket(), persistent), result.getMetadata(), decompressors, this, container, context);
		}
	}

	private boolean topDontCompress = false;
	private short topCompatibilityMode = 0;
	
	/**
	 * Handle the current metadata. I.e. do something with it: transition to a splitfile, look up a manifest, etc.
	 * LOCKING: Synchronized as it changes so many variables; if we want to write the structure to disk, we don't
	 * want this running at the same time.
	 * LOCKING: Therefore it should not directly call e.g. onFailed, innerWrapHandleMetadata, other stuff that might 
	 * cause lots of stuff to happen on other objects, eventually ClientRequestScheduler gets locked -> deadlock. This is
	 * irrelevant for persistent requests however, as they are single thread.
	 * @throws FetchException
	 * @throws MetadataParseException
	 * @throws ArchiveFailureException
	 * @throws ArchiveRestartException
	 */
	private synchronized void handleMetadata(final ObjectContainer container, final ClientContext context) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		if(persistent) {
			container.activate(this, 2);
			// ,1's are probably redundant
			container.activate(metadata, Integer.MAX_VALUE);
			container.activate(metaStrings, Integer.MAX_VALUE);
			container.activate(thisKey, 5);
			container.activate(ctx, 2); // for event producer and allowed mime types
			if(ah != null)
				ah.activateForExecution(container);
			container.activate(parent, 1);
			container.activate(actx, 5);
			container.activate(clientMetadata, 5);
			container.activate(rcb, 1);
		}
		if(uri == null) {
			if(container != null) {
				if(container.ext().isActive(this))
					throw new NullPointerException("SFI "+this+" is active and uri is null!");
				else
					throw new NullPointerException("SFI "+this+" is not active!");
			} else
				throw new NullPointerException("uri = null on transient SFI?? "+this);
		}
		synchronized(this) {
			if(cancelled)
				return;
			// So a SingleKeyListener isn't created.
			finished = true;
		}
		while(true) {
			if(metaSnoop != null) {
				if(persistent)
					container.activate(metaSnoop, 1);
				if(metaSnoop.snoopMetadata(metadata, container, context)) {
					cancel(container, context);
					if(persistent)
						container.deactivate(metaSnoop, 1);
					return;
				}
				if(persistent)
					container.deactivate(metaSnoop, 1);
			}
			if(metaStrings.size() == 0) {
				if(metadata.hasTopData()) {
					if((metadata.topSize > ctx.maxOutputLength) ||
							(metadata.topCompressedSize > ctx.maxTempLength)) {
						// Just in case...
						if(persistent) removeFrom(container, context);
						if(metadata.isSimpleRedirect() || metadata.isSplitfile()) clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
						throw new FetchException(FetchException.TOO_BIG, metadata.topSize, true, clientMetadata.getMIMEType());
					}
					rcb.onExpectedTopSize(metadata.topSize, metadata.topCompressedSize, metadata.topBlocksRequired, metadata.topBlocksTotal, container, context);
					topCompatibilityMode = metadata.getTopCompatibilityCode();
					topDontCompress = metadata.getTopDontCompress();
				}
				HashResult[] hashes = metadata.getHashes();
				if(hashes != null) {
					rcb.onHashes(hashes, container, context);
				}
			}
			if(metadata.isSimpleManifest()) {
				if(logMINOR) Logger.minor(this, "Is simple manifest");
				String name;
				if(metadata.countDocuments() == 1 && metadata.getDocument("") != null && metadata.getDocument("").isSimpleManifest()) {
					Logger.error(this, "Manifest is called \"\" for "+this, new Exception("error"));
					name = "";
				} else if(metaStrings.isEmpty()) {
					FreenetURI u = uri;
					String last = u.lastMetaString();
					if(last == null || !last.equals(""))
						u = u.addMetaStrings(new String[] { "" });
					else
						u = null;
					throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, u);
				}
				else name = removeMetaString();
				// Since metadata is a document, we just replace metadata here
				if(logMINOR) Logger.minor(this, "Next meta-string: "+name+" length "+name.length()+" for "+this);
				if(name == null) {
					if(!persistent) {
						metadata = metadata.getDefaultDocument();
					} else {
						Metadata newMeta = metadata.grabDefaultDocument();
						metadata.removeFrom(container);
						metadata = newMeta;
						container.store(this);
						container.store(metaStrings);
					}
					if(metadata == null)
						throw new FetchException(FetchException.NOT_ENOUGH_PATH_COMPONENTS, -1, false, null, uri.addMetaStrings(new String[] { "" }));
				} else {
					if(!persistent) {
						Metadata origMd = metadata;
						metadata = origMd.getDocument(name);
						if (metadata != null && metadata.isSymbolicShortlink()) {
							String oldName = name;
							name = metadata.getSymbolicShortlinkTargetName();
							if (oldName.equals(name)) throw new FetchException(FetchException.INVALID_METADATA, "redirect loop: "+name);
							metadata = origMd.getDocument(name);
						}
						thisKey = thisKey.pushMetaString(name);
					} else {
						Metadata newMeta = metadata.grabDocument(name);
						if (newMeta != null && newMeta.isSymbolicShortlink()) {
							String oldName = name;
							name = newMeta.getSymbolicShortlinkTargetName();
							if (oldName.equals(name)) throw new FetchException(FetchException.INVALID_METADATA, "redirect loop: "+name);
							newMeta = metadata.getDocument(name);
						}
						metadata.removeFrom(container);
						metadata = newMeta;
						FreenetURI oldThisKey = thisKey;
						thisKey = thisKey.pushMetaString(name);
						container.store(this);
						container.store(metaStrings);
						container.store(thisKey);
						oldThisKey.removeFrom(container);
					}
					if(metadata == null)
						throw new FetchException(FetchException.NOT_IN_ARCHIVE, "can't find "+name);
				}
				continue; // loop
			} else if(metadata.isArchiveManifest()) {
				if(logMINOR) Logger.minor(this, "Is archive manifest (type="+metadata.getArchiveType()+" codec="+metadata.getCompressionCodec()+')');
				if(metaStrings.isEmpty() && ctx.returnZIPManifests) {
					// Just return the archive, whole.
					metadata.setSimpleRedirect();
					if(persistent) container.store(metadata);
					continue;
				}
				// First we need the archive metadata.
				// Then parse it. Then we may need to fetch something from inside the archive.
				// It's more efficient to keep the existing ah if we can, and it is vital in
				// the case of binary blobs.
				if(ah == null || !ah.getKey().equals(thisKey))
					ah = context.archiveManager.makeHandler(thisKey, metadata.getArchiveType(), metadata.getCompressionCodec(),
							(parent instanceof ClientGetter ? ((ClientGetter)parent).collectingBinaryBlob() : false), persistent);
				archiveMetadata = metadata;
				metadata = null; // Copied to archiveMetadata, so do not need to clear it
				// ah is set. This means we are currently handling an archive.
				Bucket metadataBucket;
				metadataBucket = ah.getMetadata(actx, context.archiveManager, persistent ? container : null);
				if(metadataBucket != null) {
					try {
						metadata = Metadata.construct(metadataBucket);
						metadataBucket.free();
					} catch (IOException e) {
						// Bucket error?
						throw new FetchException(FetchException.BUCKET_ERROR, e);
					}
					if(persistent) container.store(this);
				} else {
					final boolean persistent = this.persistent;
					fetchArchive(false, archiveMetadata, ArchiveManager.METADATA_NAME, new ArchiveExtractCallback() {
						@Override
						public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							if(logMINOR) Logger.minor(this, "gotBucket on "+SingleFileFetcher.this+" persistent="+persistent);
							try {
								metadata = Metadata.construct(data);
								data.free();
								if(persistent) data.removeFrom(container);
								innerWrapHandleMetadata(true, container, context);
							} catch (MetadataParseException e) {
								// Invalid metadata
								onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, container, context);
								return;
							} catch (IOException e) {
								// Bucket error?
								onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, container, context);
								return;
							}
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void notInArchive(ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							onFailure(new FetchException(FetchException.INTERNAL_ERROR, "No metadata in container! Cannot happen as ArchiveManager should synthesise some!"), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void removeFrom(ObjectContainer container) {
							container.delete(this);
						}
					}, container, context); // will result in this function being called again
					if(persistent) container.store(this);
					return;
				}
				metadataBucket.free();
				continue;
			} else if(metadata.isArchiveMetadataRedirect()) {
				if(logMINOR) Logger.minor(this, "Is archive-metadata");
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive manifest");
				String filename = metadata.getArchiveInternalName();
				if(logMINOR) Logger.minor(this, "Fetching "+filename);
				Bucket dataBucket = ah.get(filename, actx, context.archiveManager, persistent ? container : null);
				if(dataBucket != null) {
					if(logMINOR) Logger.minor(this, "Returning data");
					final Metadata newMetadata;
					try {
						
						newMetadata = Metadata.construct(dataBucket);
						dataBucket.free();
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					removeMetadata(container);
					synchronized(this) {
						metadata = newMetadata;
					}
					if(persistent) {
						container.store(newMetadata);
						container.store(SingleFileFetcher.this);
					}
					continue;
				} else {
					if(logMINOR) Logger.minor(this, "Fetching archive (thisKey="+thisKey+ ')');
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					final boolean persistent = this.persistent;
					fetchArchive(true, archiveMetadata, filename, new ArchiveExtractCallback() {
						@Override
						public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
							if(persistent) {
								container.activate(SingleFileFetcher.this, 1);
							}
							if(logMINOR) Logger.minor(this, "Returning data");
							final Metadata newMetadata;
							try {
								newMetadata = Metadata.construct(data);
								removeMetadata(container);
								synchronized(SingleFileFetcher.this) {
									metadata = newMetadata;
								}
								if(persistent) {
									container.store(newMetadata);
									container.store(SingleFileFetcher.this);
								}
								innerWrapHandleMetadata(true, container, context);
							} catch (IOException e) {
								onFailure(new FetchException(FetchException.BUCKET_ERROR), false, container, context);
							} catch (MetadataParseException e) {
								onFailure(new FetchException(FetchException.INVALID_METADATA), false, container, context);
							} finally {
								data.free();
							}
							if(persistent) {
								container.deactivate(SingleFileFetcher.this, 1);
							}
						}
						@Override
						public void notInArchive(ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							onFailure(new FetchException(FetchException.NOT_IN_ARCHIVE), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void removeFrom(ObjectContainer container) {
							container.delete(this);
						}
					}, container, context);
					removeMetadata(container);
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isArchiveInternalRedirect()) {
				if(logMINOR) Logger.minor(this, "Is archive-internal redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata());
				if(persistent) container.store(clientMetadata);
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime, container, context);
				if(metaStrings.isEmpty() && isFinal && clientMetadata.getMIMETypeNoParams() != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(clientMetadata.getMIMETypeNoParams())) {
					throw new FetchException(FetchException.WRONG_MIME_TYPE, -1, false, clientMetadata.getMIMEType());
				}
				// Fetch it from the archive
				if(ah == null)
					throw new FetchException(FetchException.UNKNOWN_METADATA, "Archive redirect not in an archive manifest");
				String filename = metadata.getArchiveInternalName();
				if(logMINOR) Logger.minor(this, "Fetching "+filename);
				Bucket dataBucket = ah.get(filename, actx, context.archiveManager, persistent ? container : null);
				if(dataBucket != null) {
					if(logMINOR) Logger.minor(this, "Returning data");
					final Bucket out;
					try {
						// Data will not be freed until client is finished with it.
						if(persistent) {
							out = context.persistentBucketFactory.makeBucket(dataBucket.size());
							BucketTools.copy(dataBucket, out);
							dataBucket.free();
						} else {
							out = dataBucket;
						}
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					// Return the data
					onSuccess(new FetchResult(clientMetadata, out), container, context);
					
					return;
				} else {
					if(logMINOR) Logger.minor(this, "Fetching archive (thisKey="+thisKey+ ')');
					// Metadata cannot contain pointers to files which don't exist.
					// We enforce this in ArchiveHandler.
					// Therefore, the archive needs to be fetched.
					final boolean persistent = this.persistent;
					fetchArchive(true, archiveMetadata, filename, new ArchiveExtractCallback() {
						@Override
						public void gotBucket(Bucket data, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							if(logMINOR) Logger.minor(this, "Returning data");
							// Because this will be processed immediately, and because the callback uses a StreamGenerator, 
							// we can simply pass in the output bucket, even if it is not persistent.
							// If we ever change it so a StreamGenerator can be saved, we'll have to copy here.
							// Transient buckets should throw if attempted to store.
							onSuccess(new FetchResult(clientMetadata, data), container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void notInArchive(ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							onFailure(new FetchException(FetchException.NOT_IN_ARCHIVE), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveRestartException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void onFailed(ArchiveFailureException e, ObjectContainer container, ClientContext context) {
							if(persistent)
								container.activate(SingleFileFetcher.this, 1);
							SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
							if(persistent)
								container.deactivate(SingleFileFetcher.this, 1);
						}
						@Override
						public void removeFrom(ObjectContainer container) {
							container.delete(this);
						}
					}, container, context);
					removeMetadata(container);
					// Will call back into this function when it has been fetched.
					return;
				}
			} else if(metadata.isMultiLevelMetadata()) {
				if(logMINOR) Logger.minor(this, "Is multi-level metadata");
				// Fetch on a second SingleFileFetcher, like with archives.
				metadata.setSimpleRedirect();
				final SingleFileFetcher f = new SingleFileFetcher(this, persistent, false, metadata, new MultiLevelMetadataCallback(), ctx, container, context);
				// Clear our own metadata so it can be garbage collected, it will be replaced by whatever is fetched.
				// The new fetcher has our metadata so we don't need to removeMetadata().
				this.metadata = null;
				if(persistent) container.store(this);
				if(persistent) container.store(f);
				// We must transition to the sub-fetcher so that if the request is cancelled, it will get deleted.
				parent.onTransition(this, f, container);
				
				if(persistent) {
					f.innerWrapHandleMetadata(true, container, context);
					container.deactivate(f, 1);
				} else {
					// Break locks. Must not call onFailure(), etc, from within SFF lock.
					context.mainExecutor.execute(new Runnable() {

						@Override
						public void run() {
							f.innerWrapHandleMetadata(true, container, context);
						}
						
					});
				}
				return;
			} else if(metadata.isSingleFileRedirect()) {
				if(logMINOR) Logger.minor(this, "Is single-file redirect");
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				if(persistent) container.store(clientMetadata);
				String mime = clientMetadata.getMIMEType();
				if(mime != null) rcb.onExpectedMIME(mime, container, context);

				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.ARCHIVE_TYPE.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					if(persistent) container.store(metadata);
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(persistent) container.store(clientMetadata);
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
				ArrayList<String> newMetaStrings = newURI.listMetaStrings();
				
				// Move any new meta strings to beginning of our list of remaining meta strings
				while(!newMetaStrings.isEmpty()) {
					String o = newMetaStrings.remove(newMetaStrings.size()-1);
					metaStrings.add(0, o);
					addedMetaStrings++;
				}

				final SingleFileFetcher f = new SingleFileFetcher(parent, rcb, clientMetadata, redirectedKey, metaStrings, this.uri, addedMetaStrings, ctx, deleteFetchContext, realTimeFlag, actx, ah, archiveMetadata, maxRetries, recursionLevel, false, token, true, isFinal, topDontCompress, topCompatibilityMode, container, context, false);
				this.deleteFetchContext = false;
				if((redirectedKey instanceof ClientCHK) && !((ClientCHK)redirectedKey).isMetadata()) {
					rcb.onBlockSetFinished(this, container, context);
					byte [] redirectedCryptoKey = ((ClientCHK)redirectedKey).getCryptoKey();
					if (key instanceof ClientCHK && !Arrays.equals(
							((ClientCHK)key).getCryptoKey(),
							redirectedCryptoKey))
						redirectedCryptoKey = null;
					// not splitfile, synthesize CompatibilityMode event
					if (metadata.getParsedVersion() == 0)
						rcb.onSplitfileCompatibilityMode(
								CompatibilityMode.COMPAT_1250_EXACT,
								CompatibilityMode.COMPAT_1251,
								null,
								!((ClientCHK)redirectedKey).isCompressed(),
								true, true,
								container, context);
					else if (metadata.getParsedVersion() == 1)
						rcb.onSplitfileCompatibilityMode(
								CompatibilityMode.COMPAT_1255,
								CompatibilityMode.COMPAT_1255,
								redirectedCryptoKey,
								!((ClientCHK)redirectedKey).isCompressed(),
								true, true,
								container, context);
					else
						rcb.onSplitfileCompatibilityMode(
								CompatibilityMode.COMPAT_UNKNOWN,
								CompatibilityMode.COMPAT_UNKNOWN,
								redirectedCryptoKey,
								!((ClientCHK)redirectedKey).isCompressed(),
								true, true,
								container, context);
				}
				if(metadata.isCompressed()) {
					COMPRESSOR_TYPE codec = metadata.getCompressionCodec();
					f.addDecompressor(codec);
				}
				parent.onTransition(this, f, container);
				if(persistent) {
					container.store(metaStrings);
					container.store(f); // Store *before* scheduling to avoid activation problems.
					container.store(this);
				}
				f.schedule(container, context);
				// All done! No longer our problem!
				archiveMetadata = null; // passed on
				if(persistent) removeFrom(container, context);
				return;
			} else if(metadata.isSplitfile()) {
				if(logMINOR) Logger.minor(this, "Fetching splitfile");
				
				clientMetadata.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
				if(persistent) container.store(clientMetadata);
				
				String mimeType = clientMetadata.getMIMETypeNoParams();
				if(mimeType != null && ArchiveManager.ARCHIVE_TYPE.isUsableArchiveType(mimeType) && metaStrings.size() > 0) {
					// Looks like an implicit archive, handle as such
					metadata.setArchiveManifest();
					// Pick up MIME type from inside archive
					clientMetadata.clear();
					if(persistent) {
						container.store(metadata);
						container.store(clientMetadata);
					}
					if(logMINOR) Logger.minor(this, "Handling implicit container... (splitfile)");
					continue;
				} else {
					String mime = clientMetadata.getMIMEType();
					if(mime != null) rcb.onExpectedMIME(mime, container, context);
				}
				
				if(metaStrings.isEmpty() && isFinal && mimeType != null && ctx.allowedMIMETypes != null &&
						!ctx.allowedMIMETypes.contains(mimeType)) {
					// Just in case...
					long len = metadata.uncompressedDataLength();
					if(persistent) removeFrom(container, context);
					throw new FetchException(FetchException.WRONG_MIME_TYPE, len, false, clientMetadata.getMIMEType());
				}
				
				// Splitfile (possibly compressed)
				
				if(metadata.isCompressed()) {
					COMPRESSOR_TYPE codec = metadata.getCompressionCodec();
					addDecompressor(codec);
					if(persistent)
						container.store(decompressors);
				}
				
				if(isFinal && !ctx.ignoreTooManyPathComponents) {
					if(!metaStrings.isEmpty()) {
						// Some meta-strings left
						if(addedMetaStrings > 0) {
							// Should this be an error?
							// It would be useful to be able to fetch the data ...
							// On the other hand such inserts could cause unpredictable results?
							// Would be useful to make a redirect to the key we actually fetched.
							rcb.onFailure(new FetchException(FetchException.INVALID_METADATA, "Invalid metadata: too many path components in redirects", thisKey), this, container, context);
						} else {
							// TOO_MANY_PATH_COMPONENTS
							// report to user
							FreenetURI tryURI = uri;
							tryURI = tryURI.dropLastMetaStrings(metaStrings.size());
							rcb.onFailure(new FetchException(FetchException.TOO_MANY_PATH_COMPONENTS, metadata.uncompressedDataLength(), (rcb == parent), clientMetadata.getMIMEType(), tryURI), this, container, context);
						}
						// Just in case...
						if(persistent) removeFrom(container, context);
						return;
					}
				} else
					if(logMINOR) Logger.minor(this, "Not finished: rcb="+rcb+" for "+this); 
				
				final long len = metadata.dataLength();
				final long uncompressedLen = metadata.isCompressed() ? metadata.uncompressedDataLength() : len;
				
				if((uncompressedLen > ctx.maxOutputLength) ||
						(len > ctx.maxTempLength)) {
					// Just in case...
					boolean compressed = metadata.isCompressed();
					if(persistent) removeFrom(container, context);
					throw new FetchException(FetchException.TOO_BIG, uncompressedLen, isFinal && decompressors.size() <= (compressed ? 1 : 0), clientMetadata.getMIMEType());
				}
				
				SplitFileFetcher sf = new SplitFileFetcher(metadata, rcb, parent, ctx, deleteFetchContext, 
						realTimeFlag, decompressors, clientMetadata, actx, recursionLevel, token, topDontCompress, topCompatibilityMode, container, context);
				this.deleteFetchContext = false;
				if(persistent) {
					container.store(sf); // Avoid problems caused by storing a deactivated sf
					if(!container.ext().isActive(parent)) {
						container.activate(parent, 1);
						Logger.error(this, "Not active: "+parent);
					}
				}
				parent.onTransition(this, sf, container);
				try {
					sf.schedule(container, context);
				} catch (KeyListenerConstructionException e) {
					onFailure(e.getFetchException(), false, container, context);
					if(persistent) container.deactivate(sf, 1);
					return;
				}
				if(persistent) container.deactivate(sf, 1);
				rcb.onBlockSetFinished(this, container, context);
				// Clear our own metadata, we won't need it any more.
				// Note that SplitFileFetcher() above will have used the keys from the metadata,
				// and will have removed them from it so they don't get removed here.
				// Lack of garbage collection in db4o is a PITA!
				// For multi-level metadata etc see above.
				if(persistent) removeFrom(container, context);
				return;
			} else {
				Logger.error(this, "Don't know what to do with metadata: "+metadata);
				removeMetadata(container);
				throw new FetchException(FetchException.UNKNOWN_METADATA);
			}
		}
	}
	
	private String removeMetaString() {
		String name = metaStrings.remove(0);
		if(addedMetaStrings > 0) addedMetaStrings--;
		return name;
	}

	private void addDecompressor(COMPRESSOR_TYPE codec) {
		if(logMINOR)
			Logger.minor(this, "Adding decompressor: "+codec+" on "+this, new Exception("debug"));
		decompressors.add(codec);
	}

	private void fetchArchive(boolean forData, Metadata meta, String element, ArchiveExtractCallback callback, final ObjectContainer container, final ClientContext context) throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
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
		// FIXME arguable archive data is "temporary", but
		// this will use ctx.maxOutputLength
		f = new SingleFileFetcher(this, persistent, true, newMeta, new ArchiveFetcherCallback(forData, element, callback), new FetchContext(ctx, FetchContext.SET_RETURN_ARCHIVES, true, null), container, context);
		if(persistent) container.store(f);
		if(logMINOR) Logger.minor(this, "fetchArchive(): "+f);
		// Fetch the archive. The archive fetcher callback will unpack it, and either call the element 
		// callback, or just go back around handleMetadata() on this, which will see that the data is now
		// available.
		
		// We need to transition here, so that everything gets deleted if we are cancelled during the archive fetch phase.
		if(persistent) container.activate(parent, 1);
		parent.onTransition(this, f, container);
		
		if(!persistent) {
			// Break locks. Must not call onFailure(), etc, from within SFF lock.
			context.mainExecutor.execute(new Runnable() {

				@Override
				public void run() {
					f.innerWrapHandleMetadata(true, container, context);
				}
				
			});
		} else {
			f.innerWrapHandleMetadata(true, container, context);
			container.deactivate(f, 1);
		}
	}

	// LOCKING: If transient, DO NOT call this method from within handleMetadata.
	protected void innerWrapHandleMetadata(boolean notFinalizedSize, ObjectContainer container, ClientContext context) {
		try {
			handleMetadata(container, context);
		} catch (MetadataParseException e) {
			onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, container, context);
		} catch (FetchException e) {
			if(notFinalizedSize)
				e.setNotFinalizedSize();
			onFailure(e, false, container, context);
		} catch (ArchiveFailureException e) {
			onFailure(new FetchException(e), false, container, context);
		} catch (ArchiveRestartException e) {
			onFailure(new FetchException(e), false, container, context);
		}
	}

	class ArchiveFetcherCallback implements GetCompletionCallback {

		private final boolean wasFetchingFinalData;
		private final String element;
		private final ArchiveExtractCallback callback;
		/** For activation we need to know whether we are persistent even though the parent may not have been activated yet */
		private final boolean persistent;
		private HashResult[] hashes;
		private final FetchContext ctx;
		
		ArchiveFetcherCallback(boolean wasFetchingFinalData, String element, ArchiveExtractCallback cb) {
			this.wasFetchingFinalData = wasFetchingFinalData;
			this.element = element;
			this.callback = cb;
			this.persistent = SingleFileFetcher.this.persistent;
			this.ctx = SingleFileFetcher.this.ctx;
		}
		
		@Override
		public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context) {
			OutputStream output = null;
			PipedInputStream pipeIn = new PipedInputStream();
			PipedOutputStream pipeOut = new PipedOutputStream();
			Bucket data = null;
			if(persistent) {
				container.activate(decompressors, 5);
				container.activate(ctx, 2);
			}
			// FIXME not strictly correct and unnecessary - archive size already checked against ctx.max*Length inside SingleFileFetcher
			long maxLen = Math.min(ctx.maxTempLength, ctx.maxOutputLength);
			try {
				data = context.getBucketFactory(persistent).makeBucket(maxLen);
				output = data.getOutputStream();
				if(decompressors != null) {
					if(logMINOR) Logger.minor(this, "decompressing...");
					pipeOut.connect(pipeIn);
					DecompressorThreadManager decompressorManager =  new DecompressorThreadManager(pipeIn, decompressors, maxLen);
					pipeIn = decompressorManager.execute();
					ClientGetWorkerThread worker = new ClientGetWorkerThread(pipeIn, output, null, null, null, false, null, null, null, context.linkFilterExceptionProvider);
					worker.start();
					streamGenerator.writeTo(pipeOut, container, context);
					decompressorManager.waitFinished();
					worker.waitFinished();
				} else streamGenerator.writeTo(output, container, context);

				output.close();
				pipeOut.close();
				pipeIn.close();
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Failing above attempted fetch...");
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), state, container, context);
				return;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), state, container, context);
				return;
			} finally {
				Closer.close(pipeOut);
				Closer.close(pipeIn);
				Closer.close(output);
			}
			if(key instanceof ClientSSK) {
				// Fetching the container is essentially a full success, we should update the latest known good.
				context.uskManager.checkUSK(uri, persistent, container, false);
			}

			if(!persistent) {
				// Run directly - we are running on some thread somewhere, don't worry about it.
				parent.onTransition(state, SingleFileFetcher.this, container);
				innerSuccess(data, container, context);
			} else {
				boolean wasActive;
				// We are running on the database thread.
				// Add a tag, unpack on a separate thread, copy the data to a persistent bucket, then schedule on the database thread,
				// remove the tag, and call the callback.
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				if(!wasActive)
					container.activate(SingleFileFetcher.this, 1);
				container.activate(parent, 1);
				parent.onTransition(state, SingleFileFetcher.this, container);
				if(persistent)
					container.activate(actx, 1);
				ah.activateForExecution(container);
				ah.extractPersistentOffThread(data, true, actx, element, callback, container, context);
				if(!wasActive)
					container.deactivate(SingleFileFetcher.this, 1);
				if(state != null)
					state.removeFrom(container, context);
				container.delete(this);
				if(hashes != null) {
					for(HashResult res : hashes) {
						container.activate(res, Integer.MAX_VALUE);
						res.removeFrom(container);
					}
				}
			}
		}

		private void innerSuccess(Bucket data, ObjectContainer container, ClientContext context) {
			try {
				if(hashes != null) {
					InputStream is = null;
					try {
						if(persistent()) container.activate(hashes, Integer.MAX_VALUE);
						is = data.getInputStream();
						MultiHashInputStream hasher = new MultiHashInputStream(is, HashResult.makeBitmask(hashes));
						byte[] buf = new byte[32768];
						while(hasher.read(buf) > 0);
						hasher.close();
						is = null;
						HashResult[] results = hasher.getResults();
						if(!HashResult.strictEquals(results, hashes)) {
							onFailure(new FetchException(FetchException.CONTENT_HASH_FAILED), SingleFileFetcher.this, container, context);
							return;
						}
					} catch (IOException e) {
						onFailure(new FetchException(FetchException.BUCKET_ERROR, e), SingleFileFetcher.this, container, context);
						return;
					} finally {
						Closer.close(is);
					}
				}
				ah.extractToCache(data, actx, element, callback, context.archiveManager, container, context);
			} catch (ArchiveFailureException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
				return;
			} catch (ArchiveRestartException e) {
				SingleFileFetcher.this.onFailure(new FetchException(e), false, container, context);
				return;
			} finally {
				data.free();
				if(persistent) data.removeFrom(container);
			}
			if(callback != null) return;
			innerWrapHandleMetadata(true, container, context);
		}

		@Override
		public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				if(!wasActive)
					container.activate(SingleFileFetcher.this, 1);
			}
			// Force fatal as the fetcher is presumed to have made a reasonable effort.
			SingleFileFetcher.this.onFailure(e, true, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(persistent) {
				if(state != null)
					state.removeFrom(container, context);
				container.delete(this);
				callback.removeFrom(container);
				if(hashes != null) {
					for(HashResult res : hashes) {
						container.activate(res, Integer.MAX_VALUE);
						res.removeFrom(container);
					}
				}
			}
		}

		@Override
		public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				if(!wasActive)
					container.activate(SingleFileFetcher.this, 1);
			}
			if(persistent)
				container.activate(rcb, 1);
			if(wasFetchingFinalData) {
				rcb.onBlockSetFinished(SingleFileFetcher.this, container, context);
			}
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
		}

		@Override
		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

		@Override
		public void onExpectedMIME(String mime, ObjectContainer container, ClientContext context) {
			// Ignore
		}

		@Override
		public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				if(!wasActive)
					container.activate(SingleFileFetcher.this, 1);
			}
			if(persistent)
				container.activate(rcb, 1);
			rcb.onExpectedSize(size, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
		}

		@Override
		public void onFinalizedMetadata(ObjectContainer container) {
			// Ignore
		}

		@Override
		public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
			// Ignore
		}

		@Override
		public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean dontCompress, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			boolean cbWasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				container.activate(SingleFileFetcher.this, 1);
				cbWasActive = container.ext().isActive(rcb);
				container.activate(rcb, 1);
			}
			// This is fetching an archive, which may or may not contain the file we are looking for (it includes metadata).
			// So we are definitely not the bottom layer nor definitive.
			rcb.onSplitfileCompatibilityMode(min, max, splitfileKey, dontCompress, false, false, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(!cbWasActive)
				container.deactivate(rcb, 1);
		}

		@Override
		public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context) {
			this.hashes = hashes;
			if(persistent) container.store(this);
		}

	}

	class MultiLevelMetadataCallback implements GetCompletionCallback {
		
		private final boolean persistent;
		private final FetchContext ctx;
		
		MultiLevelMetadataCallback() {
			this.persistent = SingleFileFetcher.this.persistent;
			this.ctx = SingleFileFetcher.this.ctx;
		}
		
		@Override
		public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata, List<? extends Compressor> decompressors, ClientGetState state, ObjectContainer container, ClientContext context) {
			OutputStream output = null;
			PipedInputStream pipeIn = new PipedInputStream();
			PipedOutputStream pipeOut = new PipedOutputStream();
			Bucket finalData = null;
			if(persistent) {
				container.activate(decompressors, 5);
				container.activate(ctx, 2);
			}
			// does matter only on pre-1255 keys (1255 keys have top block sizes)
			// FIXME would save at most few tics on decompression
			// and block allocation;
			// To be effective should try guess minimal possible size earlier by the number of segments
			long maxLen = Math.min(ctx.maxTempLength, ctx.maxOutputLength);
			try {
				finalData = context.getBucketFactory(persistent).makeBucket(maxLen);
				output = finalData.getOutputStream();
				if(decompressors != null) {
					if(logMINOR) Logger.minor(this, "decompressing...");
					pipeIn.connect(pipeOut);
					DecompressorThreadManager decompressorManager =  new DecompressorThreadManager(pipeIn, decompressors, maxLen);
					pipeIn = decompressorManager.execute();
					ClientGetWorkerThread worker = new ClientGetWorkerThread(pipeIn, output, null, null, null, false, null, null, null, context.linkFilterExceptionProvider);
					worker.start();
					streamGenerator.writeTo(pipeOut, container, context);
					decompressorManager.waitFinished();
					worker.waitFinished();
				} else streamGenerator.writeTo(output, container, context);

				pipeOut.close();
				pipeIn.close();
				output.close();
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
				System.err.println("Failing above attempted fetch...");
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, e), state, container, context);
				return;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				onFailure(new FetchException(FetchException.INTERNAL_ERROR, t), state, container, context);
				return;
			} finally {
				Closer.close(pipeOut);
				Closer.close(pipeIn);
				Closer.close(output);
			}

			boolean wasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				container.activate(SingleFileFetcher.this, 1);
				container.activate(parent, 1);
			}
			try {
				parent.onTransition(state, SingleFileFetcher.this, container);
				//FIXME: Pass an InputStream here, and save ourselves a Bucket
				Metadata meta = Metadata.construct(finalData);
				removeMetadata(container);
				synchronized(SingleFileFetcher.this) {
					metadata = meta;
				}
				if(persistent) {
					container.store(meta);
					container.store(SingleFileFetcher.this);
				}
				innerWrapHandleMetadata(true, container, context);
			} catch (MetadataParseException e) {
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.INVALID_METADATA, e), false, container, context);
				return;
			} catch (IOException e) {
				// Bucket error?
				SingleFileFetcher.this.onFailure(new FetchException(FetchException.BUCKET_ERROR, e), false, container, context);
				return;
			} finally {
				finalData.free();
				if(persistent)
					finalData.removeFrom(container);
			}
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(persistent) {
				if(state != null) state.removeFrom(container, context);
				container.delete(this);
			}
		}
		
		@Override
		public void onFailure(FetchException e, ClientGetState state, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				container.activate(SingleFileFetcher.this, 1);
				container.activate(parent, 1);
			}
			parent.onTransition(state, SingleFileFetcher.this, container);
			// Pass it on; fetcher is assumed to have retried as appropriate already, so this is fatal.
			SingleFileFetcher.this.onFailure(e, true, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(persistent) {
				if(state != null)
					state.removeFrom(container, context);
				// Conceivably removeFrom() could deactivate this, so don't recheck
				container.delete(this);
			}
		}

		@Override
		public void onBlockSetFinished(ClientGetState state, ObjectContainer container, ClientContext context) {
			// Ignore as we are fetching metadata here
		}

		@Override
		public void onTransition(ClientGetState oldState, ClientGetState newState, ObjectContainer container) {
			// Ignore
		}

		@Override
		public void onExpectedMIME(String mime, ObjectContainer container, ClientContext context) {
			// Ignore
		}

		@Override
		public void onExpectedSize(long size, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			boolean cbWasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				container.activate(SingleFileFetcher.this, 1);
				cbWasActive = container.ext().isActive(rcb);
				container.activate(rcb, 1);
			}
			rcb.onExpectedSize(size, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(!cbWasActive)
				container.deactivate(rcb, 1);
		}

		@Override
		public void onFinalizedMetadata(ObjectContainer container) {
			// Ignore
		}

		@Override
		public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ObjectContainer container, ClientContext context) {
			// Ignore
		}

		@Override
		public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] splitfileKey, boolean dontCompress, boolean bottomLayer, boolean definitiveAnyway, ObjectContainer container, ClientContext context) {
			boolean wasActive = true;
			boolean cbWasActive = true;
			if(persistent) {
				wasActive = container.ext().isActive(SingleFileFetcher.this);
				container.activate(SingleFileFetcher.this, 1);
				cbWasActive = container.ext().isActive(rcb);
				container.activate(rcb, 1);
			}
			// Pass through definitiveAnyway as the top block may include the details.
			// Hence we can get them straight away rather than waiting for the bottom layer.
			rcb.onSplitfileCompatibilityMode(min, max, splitfileKey, dontCompress, false, definitiveAnyway, container, context);
			if(!wasActive)
				container.deactivate(SingleFileFetcher.this, 1);
			if(!cbWasActive)
				container.deactivate(rcb, 1);
		}

		@Override
		public void onHashes(HashResult[] hashes, ObjectContainer container, ClientContext context) {
			// Ignore
		}
		
	}
	
	/**
	 * Create a fetcher for a key.
	 */
	public static ClientGetState create(ClientRequester requester, GetCompletionCallback cb, 
			FreenetURI uri, FetchContext ctx, ArchiveContext actx, 
			int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, 
			boolean isFinal, ObjectContainer container, ClientContext context, boolean realTimeFlag, boolean hasInitialMetadata) throws MalformedURLException, FetchException {
		BaseClientKey key = null;
		if(!hasInitialMetadata)
			key = BaseClientKey.getBaseKey(uri);
		if((!uri.hasMetaStrings()) &&
				ctx.allowSplitfiles == false && ctx.followRedirects == false && 
				key instanceof ClientKey && (!hasInitialMetadata))
			return new SimpleSingleFileFetcher((ClientKey)key, maxRetries, ctx, requester, cb, isEssential, false, l, container, context, false, realTimeFlag);
		if(key instanceof ClientKey || hasInitialMetadata)
			return new SingleFileFetcher(requester, cb, null, (ClientKey)key, new ArrayList<String>(uri.listMetaStrings()), uri, 0, ctx, false, realTimeFlag, actx, null, null, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, isFinal, false, (short)0, container, context, hasInitialMetadata);
		else {
			return uskCreate(requester, realTimeFlag, cb, (USK)key, new ArrayList<String>(uri.listMetaStrings()), ctx, actx, maxRetries, recursionLevel, dontTellClientGet, l, isEssential, isFinal, container, context);
		}
	}

	private static ClientGetState uskCreate(ClientRequester requester, boolean realTimeFlag, GetCompletionCallback cb, USK usk, ArrayList<String> metaStrings, FetchContext ctx, ArchiveContext actx, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean isEssential, boolean isFinal, ObjectContainer container, ClientContext context) throws FetchException {
		if(usk.suggestedEdition >= 0) {
			// Return the latest known version but at least suggestedEdition.
			long edition = context.uskManager.lookupKnownGood(usk);
			if(edition <= usk.suggestedEdition) {
				// Background fetch - start background fetch first so can pick up updates in the datastore during registration.
				context.uskManager.startTemporaryBackgroundFetcher(usk, context, ctx, true, realTimeFlag);
				edition = context.uskManager.lookupKnownGood(usk);
				if(edition > usk.suggestedEdition) {
					if(logMINOR) Logger.minor(SingleFileFetcher.class, "Redirecting to edition "+edition);
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null, container, context);
					return null;
				} else if(edition == -1 && 
						context.uskManager.lookupLatestSlot(usk) == -1) { // We do not want to be going round and round here! 
					// Check the datastore first.
					USKFetcherTag tag = 
						context.uskManager.getFetcher(usk.copy(usk.suggestedEdition), ctx, false, requester.persistent(),
								realTimeFlag, new MyUSKFetcherCallback(requester, cb, usk, metaStrings, ctx, actx, realTimeFlag, maxRetries, recursionLevel, dontTellClientGet, l, requester.persistent(), true), false, container, context, true);
					if(isEssential)
						requester.addMustSucceedBlocks(1, container);
					return tag;
					
				} else {
					// Transition to SingleFileFetcher
					GetCompletionCallback myCB =
						new USKProxyCompletionCallback(usk, cb, requester.persistent());
					// Want to update the latest known good iff the fetch succeeds.
					SingleFileFetcher sf = 
						new SingleFileFetcher(requester, myCB, null, usk.getSSK(), metaStrings, 
								usk.getURI().addMetaStrings(metaStrings), 0, ctx, false, realTimeFlag, actx, null, null, maxRetries, recursionLevel, 
								dontTellClientGet, l, isEssential, isFinal, false, (short)0, container, context, false);
					return sf;
				}
			} else {
				cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, usk.copy(edition).getURI().addMetaStrings(metaStrings)), null, container, context);
				return null;
			}
		} else {
			// Do a thorough, blocking search
			USKFetcherTag tag = 
				context.uskManager.getFetcher(usk.copy(-usk.suggestedEdition), ctx, false, requester.persistent(),
						realTimeFlag, new MyUSKFetcherCallback(requester, cb, usk, metaStrings, ctx, actx, realTimeFlag, maxRetries, recursionLevel, dontTellClientGet, l, requester.persistent(), false), false, container, context, false);
			if(isEssential)
				requester.addMustSucceedBlocks(1, container);
			return tag;
		}
	}

	public static class MyUSKFetcherCallback implements USKFetcherTagCallback {

		final ClientRequester parent;
		final GetCompletionCallback cb;
		final USK usk;
		final ArrayList<String> metaStrings;
		final FetchContext ctx;
		final ArchiveContext actx;
		final int maxRetries;
		final int recursionLevel;
		final boolean dontTellClientGet;
		final long token;
		final boolean persistent;
		final boolean realTimeFlag;
		final boolean datastoreOnly;
		final int hashCode;
		private USKFetcherTag tag;
		
		@Override
		public void setTag(USKFetcherTag tag, ObjectContainer container, ClientContext context) {
			this.tag = tag;
			if(persistent) container.store(this);
		}
		
		public MyUSKFetcherCallback(ClientRequester requester, GetCompletionCallback cb, USK usk, ArrayList<String> metaStrings, FetchContext ctx, ArchiveContext actx, boolean realTimeFlag, int maxRetries, int recursionLevel, boolean dontTellClientGet, long l, boolean persistent, boolean datastoreOnly) {
			this.parent = requester;
			this.cb = cb;
			this.usk = usk;
			this.metaStrings = metaStrings;
			this.ctx = ctx;
			this.actx = actx;
			this.maxRetries = maxRetries;
			this.recursionLevel = recursionLevel;
			this.dontTellClientGet = dontTellClientGet;
			this.token = l;
			this.persistent = persistent;
			this.datastoreOnly = datastoreOnly;
			this.hashCode = super.hashCode();
			this.realTimeFlag = realTimeFlag;
			if(logMINOR) Logger.minor(this, "Created "+this+" for "+usk+" and "+cb+" datastore only = "+datastoreOnly);
		}
		
		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public void onFoundEdition(long l, USK newUSK, ObjectContainer container, ClientContext context, boolean metadata, short codec, byte[] data, boolean newKnownGood, boolean newSlotToo) {
			if(persistent)
				container.activate(this, 2);
			if(l < usk.suggestedEdition && datastoreOnly)
				l = usk.suggestedEdition;
			ClientSSK key = usk.getSSK(l);
			try {
				if(l == usk.suggestedEdition) {
					SingleFileFetcher sf = new SingleFileFetcher(parent, cb, null, key, metaStrings, key.getURI().addMetaStrings(metaStrings),
							0, ctx, false, realTimeFlag, actx, null, null, maxRetries, recursionLevel+1, dontTellClientGet, token, false, true, false, (short)0, container, context, false);
					if(tag != null) {
						if(persistent) container.activate(cb, 1);
						cb.onTransition(tag, sf, container);
					}
					sf.schedule(container, context);
					if(persistent) removeFrom(container);
				} else {
					if(persistent) container.activate(cb, 1);
					cb.onFailure(new FetchException(FetchException.PERMANENT_REDIRECT, newUSK.getURI().addMetaStrings(metaStrings)), null, container, context);
					if(persistent) removeFrom(container);
				}
			} catch (FetchException e) {
				if(persistent) container.activate(cb, 1);
				cb.onFailure(e, null, container, context);
				if(persistent) removeFrom(container);
			}
		}
		
		private void removeFrom(ObjectContainer container) {
			container.delete(metaStrings);
			container.delete(this);
			container.activate(usk, 5);
			usk.removeFrom(container);
		}

		@Override
		public void onFailure(ObjectContainer container, ClientContext context) {
			FetchException e = null;
			if(datastoreOnly) {
				if(persistent)
					container.activate(usk, Integer.MAX_VALUE);
				try {
					onFoundEdition(usk.suggestedEdition, usk, container, context, false, (short) -1, null, false, false);
					return;
				} catch (Throwable t) {
					e = new FetchException(FetchException.INTERNAL_ERROR, t);
				}
			}
			if(persistent)
				container.activate(this, 2);
			if(e == null) e = new FetchException(FetchException.DATA_NOT_FOUND, "No USK found");
			if(logMINOR) Logger.minor(this, "Failing USK with "+e, e);
			if(persistent) container.activate(cb, 1);
			if(cb == null)
				throw new NullPointerException("Callback is null in "+this+" for usk "+usk+" with datastoreOnly="+datastoreOnly);
			cb.onFailure(e, null, container, context);
			if(persistent) removeFrom(container);
		}

		@Override
		public void onCancelled(ObjectContainer container, ClientContext context) {
			if(persistent)
				container.activate(this, 2);
			cb.onFailure(new FetchException(FetchException.CANCELLED, (String)null), null, container, context);
			if(persistent) removeFrom(container);
		}

		@Override
		public short getPollingPriorityNormal() {
			return parent.getPriorityClass();
		}

		@Override
		public short getPollingPriorityProgress() {
			return parent.getPriorityClass();
		}

	}
	
//	public void objectOnActivate(ObjectContainer container) {
//		Logger.minor(this, "ACTIVATING: "+this, new Exception("debug"));
//	}
//	
	@Override
	public void removeFrom(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "removeFrom() on "+this);
		container.activate(uri, 5);
		uri.removeFrom(container);
		if(thisKey != null) {
			container.activate(thisKey, 5);
			thisKey.removeFrom(container);
		}
		if(ah != null) {
			ah.activateForExecution(container);
			ah.removeFrom(container);
		}
		container.activate(metaStrings, 1);
		metaStrings.clear();
		container.delete(metaStrings);
		container.activate(clientMetadata, 1);
		clientMetadata.removeFrom(container);
		// actx is global to the ClientRequest, not our problem
		container.activate(decompressors, 1);
		decompressors.clear();
		removeMetadata(container);
		removeArchiveMetadata(container);
		container.delete(decompressors);
		super.removeFrom(container, context);
	}
	
	private void removeMetadata(ObjectContainer container) {
		if(!persistent) return;
		if(logMINOR) Logger.minor(this, "removeMetadata() on "+this);
		if(metadata == null) return;
		container.activate(metadata, 1);
		metadata.removeFrom(container);
		metadata = null;
	}

	private void removeArchiveMetadata(ObjectContainer container) {
		if(!persistent) return;
		if(logMINOR) Logger.minor(this, "removeArchiveMetadata() on "+this);
		if(archiveMetadata == null) return;
		container.activate(archiveMetadata, 1);
		archiveMetadata.removeFrom(container);
		archiveMetadata = null;
	}

}
