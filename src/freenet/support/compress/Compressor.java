package freenet.support.compress;

import java.io.IOException;

import freenet.client.Metadata;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public abstract class Compressor {

	public static Compressor gzip = new GzipCompressor();

	public abstract Bucket compress(Bucket data, BucketFactory bf) throws IOException;

	public short codecNumberForMetadata() {
		return Metadata.COMPRESS_GZIP;
	}

	/** Count the number of distinct compression algorithms currently supported. */
	public static int countCompressAlgorithms() {
		// FIXME we presently only support gzip. This should change in future.
		return 1;
	}

	public static Compressor getCompressionAlgorithmByDifficulty(int i) {
		if(i == 0)
			return Compressor.gzip;
		// FIXME when we get more compression algos, put them here.
		return null;
	}

	public static Compressor getCompressionAlgorithmByMetadataID(short algo) {
		if(algo == Metadata.COMPRESS_GZIP)
			return gzip;
		// FIXME when we get more compression algos, put them here.
		return null;
	}
	
	/** Decompress in RAM only.
	 * @param dbuf Input buffer.
	 * @param i Offset to start reading from.
	 * @param j Number of bytes to read.
	 * @param output Output buffer.
	 * @throws DecompressException 
	 * @returns The number of bytes actually written.
	 */
	public abstract int decompress(byte[] dbuf, int i, int j, byte[] output) throws DecompressException;

}
