package freenet.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.client.events.DecodedBlockEvent;
import freenet.client.events.FetchedMetadataEvent;
import freenet.client.events.GotBlockEvent;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.FreenetURI;
import freenet.keys.KeyDecodeException;
import freenet.node.LowLevelGetException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;

/** Class that does the actual fetching. Does not have to have a user friendly
 * interface!
 */
class Fetcher {

	/** The original URI to be fetched. */
	final FreenetURI origURI;
	/** The settings for the fetch e.g. max file size */
	final FetcherContext ctx;
	/** The archive context object to be passed down the entire request. This is
	 * recreated if we get an ArchiveRestartException. It does loop detection, partly
	 * in order to prevent rare deadlocks.
	 */
	ArchiveContext archiveContext;
	
	/**
	 * Local-only constructor, with ArchiveContext, for recursion via e.g. archives.
	 */
	Fetcher(FreenetURI uri, FetcherContext fctx, ArchiveContext actx) {
		if(uri == null) throw new NullPointerException();
		origURI = uri;
		ctx = fctx;
		archiveContext = actx;
	}

	/**
	 * Create a Fetcher. Public constructor, for when starting a new request chain.
	 * @param uri The key to fetch.
	 * @param ctx The settings for the fetch.
	 */
	public Fetcher(FreenetURI uri, FetcherContext ctx) {
		this(uri, ctx, new ArchiveContext());
	}
	
	/**
	 * Fetch the key. Called by clients.
	 * @return The key requested's data and client metadata.
	 * @throws FetchException If we cannot fetch the key for some reason. Various
	 * other exceptions are used internally; they are converted to a FetchException
	 * by this driver routine.
	 */
	public FetchResult run() throws FetchException {
		for(int i=0;i<ctx.maxArchiveRestarts;i++) {
			try {
				ClientMetadata dm = new ClientMetadata();
				ClientKey key;
				try {
					key = ClientKey.getBaseKey(origURI);
				} catch (MalformedURLException e2) {
					throw new FetchException(FetchException.INVALID_URI, "Invalid URI: "+origURI);
				}
				LinkedList metaStrings = origURI.listMetaStrings();
				
				FetchResult fr = realRun(dm, 0, key, metaStrings, ctx.dontEnterImplicitArchives, ctx.localRequestOnly);
				
				if(metaStrings.isEmpty()) return fr;
				// Still got some meta-strings
				throw new FetchException(FetchException.HAS_MORE_METASTRINGS);
				
			} catch (ArchiveRestartException e) {
				archiveContext = new ArchiveContext();
				continue;
			} catch (MetadataParseException e) {
				throw new FetchException(e);
			} catch (ArchiveFailureException e) {
				if(e.getMessage().equals(ArchiveFailureException.TOO_MANY_LEVELS))
					throw new FetchException(FetchException.TOO_DEEP_ARCHIVE_RECURSION);
				throw new FetchException(e);
			}
		}
		throw new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
	}

	FetchResult realRun(ClientMetadata dm, int recursionLevel, FreenetURI uri, boolean dontEnterImplicitArchives, boolean localOnly) 
	throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		ClientKey key;
		try {
			key = ClientKey.getBaseKey(origURI);
		} catch (MalformedURLException e2) {
			throw new FetchException(FetchException.INVALID_URI, "Invalid URI: "+origURI);
		}
		LinkedList metaStrings = origURI.listMetaStrings();
		
		return realRun(dm, recursionLevel, key, metaStrings, dontEnterImplicitArchives, localOnly);
	}
	
	/**
	 * Fetch a key, within an overall fetch process. Called by self in recursion, and
	 * called by driver function @see run() .
	 * @param dm The client metadata object to accumulate client metadata in.
	 * @param recursionLevel The recursion level. Incremented every time we enter
	 * realRun(). If it goes above a certain limit, we throw a FetchException.
	 * @param uri The URI to fetch.
	 * @return The data, complete with client metadata.
	 * @throws FetchException If we could not fetch the data.
	 * @throws MetadataParseException If we could not parse the metadata.
	 * @throws ArchiveFailureException If we could not extract data from an archive.
	 * @throws ArchiveRestartException 
	 */
	FetchResult realRun(ClientMetadata dm, int recursionLevel, ClientKey key, LinkedList metaStrings, boolean dontEnterImplicitArchives, boolean localOnly) 
	throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		Logger.minor(this, "Running fetch for: "+key);
		recursionLevel++;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION, ""+recursionLevel+" should be < "+ctx.maxRecursionLevel);
		
		// Do the fetch
		ClientKeyBlock block;
		try {
			block = ctx.client.getKey(key, localOnly, ctx.starterClient, ctx.cacheLocalRequests);
		} catch (LowLevelGetException e) {
			switch(e.code) {
			case LowLevelGetException.DATA_NOT_FOUND:
				throw new FetchException(FetchException.DATA_NOT_FOUND);
			case LowLevelGetException.DATA_NOT_FOUND_IN_STORE:
				throw new FetchException(FetchException.DATA_NOT_FOUND);
			case LowLevelGetException.DECODE_FAILED:
				throw new FetchException(FetchException.BLOCK_DECODE_ERROR);
			case LowLevelGetException.INTERNAL_ERROR:
				throw new FetchException(FetchException.INTERNAL_ERROR);
			case LowLevelGetException.REJECTED_OVERLOAD:
				throw new FetchException(FetchException.REJECTED_OVERLOAD);
			case LowLevelGetException.ROUTE_NOT_FOUND:
				throw new FetchException(FetchException.ROUTE_NOT_FOUND);
			case LowLevelGetException.TRANSFER_FAILED:
				throw new FetchException(FetchException.TRANSFER_FAILED);
			case LowLevelGetException.VERIFY_FAILED:
				throw new FetchException(FetchException.BLOCK_DECODE_ERROR);
			default:
				Logger.error(this, "Unknown LowLevelGetException code: "+e.code);
				throw new FetchException(FetchException.INTERNAL_ERROR);
			}
		}
		
		ctx.eventProducer.produceEvent(new GotBlockEvent(key));
		
		Bucket data;
		try {
			data = block.decode(ctx.bucketFactory, (int) (Math.min(ctx.maxTempLength, Integer.MAX_VALUE)));
		} catch (KeyDecodeException e1) {
			throw new FetchException(FetchException.BLOCK_DECODE_ERROR, e1.getMessage());
		} catch (IOException e) {
			Logger.error(this, "Could not capture data - disk full?: "+e, e);
			throw new FetchException(FetchException.BUCKET_ERROR, e);
		}
		
		ctx.eventProducer.produceEvent(new DecodedBlockEvent(key));
		
		if(!block.isMetadata()) {
			// Just return the data
			return new FetchResult(dm, data);
		}
		
		// Otherwise we need to parse the metadata

		if(data.size() > ctx.maxMetadataSize)
			throw new FetchException(FetchException.TOO_BIG_METADATA);
		Metadata metadata;
		try {
			metadata = Metadata.construct(BucketTools.toByteArray(data));
		} catch (IOException e) {
			throw new FetchException(FetchException.BUCKET_ERROR, e);
		}
		
		ctx.eventProducer.produceEvent(new FetchedMetadataEvent());
		
		return runMetadata(dm, recursionLevel, key, metaStrings, metadata, null, key.getURI(), dontEnterImplicitArchives, localOnly);
	}
	
	/**
	 * Fetch data, from metadata.
	 * @param recursionLevel The recursion level, from above. Not incremented here, as we will
	 * go through realRun() if the key changes, so the number of passes here is severely limited.
	 * @param key The key being fetched.
	 * @param metaStrings List of unused meta strings (to be used by manifests).
	 * @param metadata The parsed metadata to process.
	 * @param container The container in which this metadata is found.
	 * @throws MetadataParseException If we could not parse metadata from a sub-document. Will be
	 * converted to a FetchException above.
	 * @throws ArchiveFailureException If extracting data from an archive failed.
	 * @throws FetchException If the fetch failed for some reason.
	 * @throws ArchiveRestartException 
	 */
	private FetchResult runMetadata(ClientMetadata dm, int recursionLevel, ClientKey key, LinkedList metaStrings, 
			Metadata metadata, ArchiveHandler container, FreenetURI thisKey, boolean dontEnterImplicitArchives, boolean localOnly) 
	throws MetadataParseException, FetchException, ArchiveFailureException, ArchiveRestartException {
		
		if(metadata.isSimpleManifest()) {
			String name;
			if(metaStrings.isEmpty())
				name = null;
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
			return runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
		} else if(metadata.isArchiveManifest()) {
			container = ctx.archiveManager.makeHandler(thisKey, metadata.getArchiveType(), false);
			Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
			try {
				metadata = Metadata.construct(metadataBucket);
			} catch (IOException e) {
				throw new FetchException(FetchException.BUCKET_ERROR);
			}
			return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
		} else if(metadata.isArchiveInternalRedirect()) {
			if(container == null)
				throw new FetchException(FetchException.NOT_IN_ARCHIVE);
			else {
				/* Implicit archive handling:
				 * Sooner or later we reach a SimpleFileRedirect to data, a Splitfile to data,
				 * or an ArchiveInternalRedirect to data.
				 * 
				 * In this case, if it is an archive type, if implicit archive handling is enabled, and if
				 * we have more meta-strings, we can try to enter it.
				 */
				if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
					// Possible implicit archive inside archive?
					container = ctx.archiveManager.makeHandler(thisKey, ArchiveManager.getArchiveType(dm.getMIMEType()), false);
					Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
					try {
						metadata = Metadata.construct(metadataBucket);
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
				}
				Bucket result = container.get(metadata.getZIPInternalName(), archiveContext, ctx, dm, recursionLevel, true);
				dm.mergeNoOverwrite(metadata.getClientMetadata());
				return new FetchResult(dm, result);
			}
		} else if(metadata.isMultiLevelMetadata()) {
			// Doesn't have to be a splitfile; could be from a ZIP or a plain file.
			metadata.setSimpleRedirect();
			FetchResult res = runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, true, localOnly);
			try {
				metadata = Metadata.construct(res.data);
			} catch (MetadataParseException e) {
				throw new FetchException(FetchException.INVALID_METADATA, e);
			} catch (IOException e) {
				throw new FetchException(FetchException.BUCKET_ERROR, e);
			}
			return runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
		} else if(metadata.isSingleFileRedirect()) {
			FreenetURI uri = metadata.getSingleTarget();
			dm.mergeNoOverwrite(metadata.getClientMetadata());
			if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
				// Is probably an implicit archive.
				ClientKey target;
				try {
					target = ClientKey.getBaseKey(uri);
				} catch (MalformedURLException e1) {
					throw new FetchException(FetchException.INVALID_URI, "Invalid URI: "+uri);
				}
				// Probably a usable archive as-is. We may not have to fetch it.
				container = ctx.archiveManager.makeHandler(uri, ArchiveManager.getArchiveType(dm.getMIMEType()), true);
				if(container != null) {
					Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
					try {
						metadata = Metadata.construct(metadataBucket);
					} catch (IOException e) {
						throw new FetchException(FetchException.BUCKET_ERROR);
					}
					return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
				} // else just fetch it, create context later
			}
			
			
			ClientKey newKey;
			try {
				newKey = ClientKey.getBaseKey(uri);
			} catch (MalformedURLException e2) {
				throw new FetchException(FetchException.INVALID_URI, "Invalid URI: "+uri);
			}
			
			LinkedList newMetaStrings = uri.listMetaStrings();
			
			// Move any new meta strings to beginning of our list of remaining meta strings
			while(!newMetaStrings.isEmpty()) {
				Object o = newMetaStrings.removeLast();
				metaStrings.addFirst(o);
			}
			
			FetchResult fr = realRun(dm, recursionLevel, newKey, metaStrings, dontEnterImplicitArchives, localOnly);
			if(metadata.isCompressed()) {
				Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.compressionCodec);
				Bucket data = fr.data;
				Bucket output;
				try {
					long maxLen = ctx.maxTempLength;
					if(maxLen < 0) maxLen = Long.MAX_VALUE;
					output = codec.decompress(data, ctx.bucketFactory, maxLen);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				} catch (CompressionOutputSizeException e) {
					throw new FetchException(FetchException.TOO_BIG);
				}
				return new FetchResult(fr, output);
			}
			return fr;
		} else if(metadata.isSplitfile()) {
			// Straight data splitfile.
			// Might be used by parents for something else, in which case they will set dontEnterImplicitArchives.
			dm.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
			if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
				// We know target is not metadata.
				container = ctx.archiveManager.makeHandler(thisKey, ArchiveManager.getArchiveType(dm.getMIMEType()), false);
				Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
				try {
					metadata = Metadata.construct(metadataBucket);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				}
				return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives, localOnly);
			}
			
			FetcherContext newCtx;
			if(metadata.splitUseLengths)
				newCtx = new FetcherContext(ctx, FetcherContext.SPLITFILE_USE_LENGTHS_MASK);
			else
				newCtx = new FetcherContext(ctx, FetcherContext.SPLITFILE_DEFAULT_MASK);
			
			SplitFetcher sf = new SplitFetcher(metadata, archiveContext, newCtx, recursionLevel);
			Bucket sfResult = sf.fetch(); // will throw in event of error
			if(metadata.isCompressed()) {
				Logger.minor(this, "Is compressed: "+metadata.compressionCodec);
				Compressor codec = Compressor.getCompressionAlgorithmByMetadataID(metadata.compressionCodec);
				try {
					long maxLen = ctx.maxTempLength;
					if(maxLen < 0) maxLen = Long.MAX_VALUE;
					sfResult = codec.decompress(sfResult, ctx.bucketFactory, maxLen);
				} catch (IOException e) {
					throw new FetchException(FetchException.BUCKET_ERROR, e);
				} catch (CompressionOutputSizeException e) {
					throw new FetchException(FetchException.TOO_BIG);
				}
			} else
				Logger.minor(this, "Not compressed ("+metadata.compressionCodec+")");
			return new FetchResult(dm, sfResult);
		} else {
			Logger.error(this, "Don't know what to do with metadata: "+metadata);
			throw new FetchException(FetchException.UNKNOWN_METADATA);
		}
	}
}
