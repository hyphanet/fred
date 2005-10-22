package freenet.client;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.KeyBlock;
import freenet.node.SimpleLowLevelClient;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/** Class that does the actual fetching. Does not have to have a user friendly
 * interface!
 */
class Fetcher {

	final FreenetURI origURI;
	final FetcherContext ctx;
	
	public Fetcher(FreenetURI uri, FetcherContext ctx) {
		this.origURI = uri;
		this.ctx = ctx;
	}

	/**
	 * Run the actual fetch.
	 * @return The result of the fetch - successful or not.
	 */
	public FetchResult run(int archiveRecursionLevel) {
		if(archiveRecursionLevel > ctx.maxArchiveRecursionLevel) {
			throw new FetchException(FetchException.TOO_DEEP_ARCHIVE_RECURSION);
		}
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
				return new FetchResult(dm, ctx.bucketFactory.makeImmutableBucket(data));
			}
			
			// Else need to parse the metadata
			// This will throw if it finds an error, including semi-errors
			// such as too-big-indirect-metadata
			Metadata metadata = new Metadata(data);
			
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
					key = metadata.getSingleTarget();
					if(metadata.isArchiveManifest()) {
						zip = ctx.archiveManager.makeHandler(key, archiveRecursionLevel + 1, context);
						Bucket metadataBucket = zip.getMetadata();
						metadata = new Metadata(metadataBucket);
						continue;
					}
					metadata = null;
					dm.mergeNoOverwrite(metadata.getDocumentMetadata());
					continue;
				} else if(metadata.isZIPInternalRedirect() && zip != null) {
					/** This is the whole document:
					 * Metadata: ZIP manifest -> fetch ZIP file, read .metadata
					 * .metadata: simple manifest -> look up filename ->
					 * filename's document -> is ZIP-internal-redirect
					 * Only valid if we are in a ZIP manifest.
					 * 
					 * Now, retreive the data
					 */
					Bucket result = zip.get(metadata.getZIPInternalName());
					dm.mergeNoOverwrite(metadata.getDocumentMetadata());
					return new FetchResult(dm, result);
				} else if(metadata.isSplitfile()) {
					
					int j;
					for(j=0;j<ctx.maxLevels;j++) {
					
						// FIXME need to pass in whatever settings SF wants above
						SplitFetcher sf = new SplitFetcher(metadata, ctx.maxTempLength);
						Bucket sfResult = sf.run(); // will throw in event of error
						
						if(metadata.isSimpleSplitfile()) {
							return new FetchResult(metadata.getDocumentMetadata(), sfResult);
						} else if(metadata.isMultiLevelMetadata()) {
							metadata = new Metadata(sfResult);
							if(!metadata.isMultiLevelMetadata())
								break; // try the new metadata
						} else if(metadata.isArchiveManifest()) {
							zip = ctx.archiveManager.getHandler(key, archiveRecursionLevel + 1, context);
							Bucket metadataBucket = zip.getMetadata();
							metadata = new Metadata(metadataBucket);
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
		} // loop (redirects)
		// Too many redirects
		// FIXME Throw an exception
		// TODO Auto-generated method stub
		return null;
	}
}
