package freenet.client;

import java.io.IOException;

import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public abstract class Compressor {

	public static Compressor gzip = new GzipCompressor();

	public abstract Bucket compress(Bucket data, BucketFactory bf) throws IOException;

	public int codecNumberForMetadata() {
		return Metadata.COMPRESS_GZIP;
	}

}
