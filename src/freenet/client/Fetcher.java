package freenet.client;

import java.io.IOException;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

/** Class that does the actual fetching. Does not have to have a user friendly
 * interface!
 */
class Fetcher {

	final FreenetURI origURI;
	final FetcherContext ctx;
	ArchiveContext archiveContext;
	
	public Fetcher(FreenetURI uri, FetcherContext ctx, ArchiveContext archiveContext) {
		this.origURI = uri;
		this.ctx = ctx;
		this.archiveContext = archiveContext;
	}

	public FetchResult run() throws FetchException {
		for(int i=0;i<ctx.maxArchiveRestarts;i++) {
			try {
				return realRun(false);
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
	
	/**
	 * Run the actual fetch.
	 * @param overrideZipManifest 
	 * @return The result of the fetch - successful or not.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 * @throws ArchiveFailureException 
	 */
	public FetchResult realRun(boolean overrideZipManifest) throws FetchException, ArchiveRestartException, MetadataParseException, ArchiveFailureException {
		FreenetURI uri = origURI;
		ClientKey key = ClientKey.get(origURI);
		ClientMetadata dm = new ClientMetadata();
		ArchiveHandler zip = null;
		
		for(int i=0;i<ctx.maxRedirects;i++) {
			// We have moved on to a new key, so we are not in the same ZIP
			zip = null;
			
			// Fetch the first key
			KeyBlock block = ctx.client.getKey(key);
			
			byte[] data;
			try {
				data = block.decode(key);
			} catch (KeyDecodeException e1) {
				throw new FetchException(FetchException.BLOCK_DECODE_ERROR);
			}
			
			if(!key.isMetadata()) {
				// Just return the data
				try {
					return new FetchResult(dm, BucketTools.makeImmutableBucket(ctx.bucketFactory, data));
				} catch (IOException e) {
					Logger.error(this, "Could not capture data - disk full?: "+e, e);
				}
			}
			
			// Else need to parse the metadata
			// This will throw if it finds an error, including semi-errors
			// such as too-big-indirect-metadata
			Metadata metadata = Metadata.construct(data);
			if(overrideZipManifest) {
				if(metadata.isArchiveManifest())
					metadata.setSimpleRedirect();
				else
					throw new ArchiveRestartException("Override zip manifest set but not a zip manifest!");
			}
			
			while(true) {

				/** Is set false every time we get a new key. */
				boolean inSplitZipManifest = false;
				
				if(metadata == null && key != null) {
					// Try next key
					break;
				}
				
				if(metadata.isSimpleManifest()) {
					// Need a name from the URI
					String name = uri.getMetaString();
					
					// Since metadata is a document, we just replace metadata here
					if(name == null) {
						metadata = metadata.getDefaultDocument();
					} else {
						metadata = metadata.getDocument(name);
						uri = uri.popMetaString();
					}
					continue; // process the new metadata
				} else if(metadata.isSingleFileRedirect()) {
					key = ClientKey.get(metadata.getSingleTarget());
					if(metadata.isArchiveManifest()) {
						zip = ctx.archiveManager.makeHandler(key, metadata.getArchiveType());
						Bucket metadataBucket = zip.getMetadata(archiveContext, ctx);
						metadata = Metadata.construct(metadataBucket);
						continue;
					}
					metadata = null;
					dm.mergeNoOverwrite(metadata.getClientMetadata());
					continue;
				} else if(metadata.isArchiveInternalRedirect() && zip != null) {
					/** This is the whole document:
					 * Metadata: ZIP manifest -> fetch ZIP file, read .metadata
					 * .metadata: simple manifest -> look up filename ->
					 * filename's document -> is ZIP-internal-redirect
					 * Only valid if we are in a ZIP manifest.
					 * 
					 * Now, retreive the data
					 */
					Bucket result = zip.get(metadata.getZIPInternalName(), archiveContext, ctx, inSplitZipManifest);
					dm.mergeNoOverwrite(metadata.getClientMetadata());
					return new FetchResult(dm, result);
				} else if(metadata.isSplitfile()) {
					
					if(metadata.isArchiveManifest()) {
						// Check the cache first
						zip = ctx.archiveManager.makeHandler(key, metadata.getArchiveType());
						// Future ArchiveInternalRedirects will point to *self*
						inSplitZipManifest = true;
						Bucket metadataBucket = zip.getMetadata(archiveContext, null);
						if(metadataBucket != null) {
							metadata = Metadata.construct(metadataBucket);
							continue;
						}
					}
					
					int j;
					for(j=0;j<ctx.maxLevels;j++) {
						SplitFetcher sf = new SplitFetcher(metadata, ctx.maxTempLength, archiveContext, ctx);
						Bucket sfResult = sf.fetch(); // will throw in event of error
						
						if(metadata.isSimpleSplitfile()) {
							return new FetchResult(metadata.getClientMetadata(), sfResult);
						} else if(metadata.isMultiLevelMetadata()) {
							metadata = Metadata.construct(sfResult);
							if(!metadata.isMultiLevelMetadata())
								break; // try the new metadata
						} else if(metadata.isArchiveManifest()) {
							// Use the new metadata
							// ZIP points to current key
							Bucket metadataBucket = zip.getMetadata(archiveContext, ctx);
							metadata = Metadata.construct(metadataBucket);
							break;
						} else {
							throw new FetchException(FetchException.UNKNOWN_SPLITFILE_METADATA);
						}
					} // loop (splitfile levels)
					if(j>=ctx.maxLevels) {
						throw new FetchException(FetchException.TOO_MANY_METADATA_LEVELS);
						// Too many levels
						// FIXME: throw something
					}
				} else {
					throw new FetchException(FetchException.UNKNOWN_METADATA);
				}
			} // loop (metadata)
		}
		// Too many redirects
		throw new FetchException(FetchException.TOO_MANY_REDIRECTS);
	}
}
