package freenet.keys;

import java.io.IOException;

import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * Interface for fetched blocks. Can be decoded with a key.
 */
public interface KeyBlock {

	/** Decode with the key
	 * @param key The ClientKey to use to decode the block. 
	 * @param factory The BucketFactory to use to create the Bucket to return the data in.
	 * @param maxLength The maximum size of the returned data in bytes.
	 */
	Bucket decode(ClientKey key, BucketFactory factory, int maxLength) throws KeyDecodeException, IOException;

}
