/*
  Compressor.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support.compress;

import java.io.IOException;

import freenet.client.Metadata;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public abstract class Compressor {

    public static final Compressor GZIP = new GzipCompressor();

	public abstract Bucket compress(Bucket data, BucketFactory bf, long maxLength) throws IOException, CompressionOutputSizeException;

	/**
	 * Decompress data.
	 * @param data The data to decompress.
	 * @param bucketFactory A BucketFactory to create a new Bucket with if necessary.
	 * @param maxLength The maximum length to decompress (we throw if more is present).
	 * @param maxEstimateSizeLength If the data is too big, and this is >0, read up to this many bytes in order to try to get the data size.
	 * @param preferred A Bucket to use instead. If null, we allocate one from the BucketFactory.
	 * @return
	 * @throws IOException
	 * @throws CompressionOutputSizeException
	 */
	public abstract Bucket decompress(Bucket data, BucketFactory bucketFactory, long maxLength, long maxEstimateSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException;

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
            return GZIP;
		// FIXME when we get more compression algos, put them here.
		return null;
	}

	public static Compressor getCompressionAlgorithmByMetadataID(short algo) {
		if(algo == Metadata.COMPRESS_GZIP)
            return GZIP;
		// FIXME when we get more compression algos, put them here.
		return null;
	}
	
	/** Decompress in RAM only.
	 * @param dbuf Input buffer.
	 * @param i Offset to start reading from.
	 * @param j Number of bytes to read.
	 * @param output Output buffer.
	 * @throws DecompressException 
	 * @throws CompressionOutputSizeException 
	 * @returns The number of bytes actually written.
	 */
	public abstract int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException;

}
