package freenet.client;

import java.io.IOException;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.KeyBlock;
import freenet.support.Bucket;
import freenet.support.Logger;

/** Class that does the actual fetching. Does not have to have a user friendly
 * interface!
 */
class Fetcher {

	final FreenetURI origURI;
	final FetcherContext ctx;
	final ArchiveContext archiveContext;
	
	public Fetcher(FreenetURI uri, FetcherContext ctx, ArchiveContext archiveContext) {
		this.origURI = uri;
		this.ctx = ctx;
		this.archiveContext = archiveContext;
	}

	public FetchResult run() throws FetchException {
		while(true) {
			try {
				return realRun();
			} catch (ArchiveRestartException e) {
				continue;
			} catch (MetadataParseException e) {
				throw new FetchException(e);
			} catch (ArchiveFailureException e) {
				if(e.getMessage().equals(ArchiveFailureException.TOO_MANY_LEVELS))
					throw new FetchException(FetchException.TOO_DEEP_ARCHIVE_RECURSION);
				throw new FetchException(e);
			}
		}
	}
	
	/**
	 * Run the actual fetch.
	 * @return The result of the fetch - successful or not.
	 * @throws FetchException 
	 * @throws MetadataParseException 
	 * @throws ArchiveFailureException 
	 */
	public FetchResult realRun() throws FetchException, ArchiveRestartException, MetadataParseException, ArchiveFailureException {
		FreenetURI uri = origURI;
		ClientKey key = ClientKey.get(origURI);
		ClientMetadata dm = new ClientMetadata();
		ArchiveHandler zip = null;
		
		for(int i=0;i<ctx.maxRedirects;i++) {
			// We have moved on to a new key, so we are not in the same ZIP
			zip = null;
			
			// Fetch the first key
			KeyBlock block = ctx.client.getKey(key);
			
			byte[] data = block.decode(key);
			
			if(!key.isMetadata()) {
				// Just return the data
				try {
					return new FetchResult(dm, ctx.bucketFactory.makeImmutableBucket(data));
				} catch (IOException e) {
					Logger.error(this, "Could not capture data - disk full?: "+e, e);
				}
			}
			
			// Else need to parse the metadata
			// This will throw if it finds an error, including semi-errors
			// such as too-big-indirect-metadata
			Metadata metadata = Metadata.construct(data);
			
			while(true) {
				
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
					Bucket result = zip.get(metadata.getZIPInternalName(), archiveContext, ctx);
					dm.mergeNoOverwrite(metadata.getClientMetadata());
					return new FetchResult(dm, result);
				} else if(metadata.isSplitfile()) {
					
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
							zip = ctx.archiveManager.makeHandler(key, metadata.getArchiveType());
							Bucket metadataBucket = zip.getMetadata(archiveContext, ctx);
							metadata = Metadata.construct(metadataBucket);
							break;
						} else {
							throw new FetchException(FetchException.UNKNOWN_SPLITFILE_METADATA);
						}
					} // loop (splitfile levels)
					if(j>=ctx.maxLevels) {
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
