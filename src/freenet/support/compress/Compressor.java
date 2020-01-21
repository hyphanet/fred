/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public interface Compressor {

	String DEFAULT_COMPRESSORDESCRIPTOR = null;

	enum COMPRESSOR_TYPE implements Compressor {
	    // WARNING: Changing non-transient members on classes that are Serializable can result in
	    // restarting downloads or losing uploads.

		// Codecs will be tried in order: put the less resource consuming first
		GZIP("GZIP", new GzipCompressor(), (short) 0),
		BZIP2("BZIP2", new Bzip2Compressor(), (short) 1),
		LZMA("LZMA", new OldLZMACompressor(), (short)2),
		LZMA_NEW("LZMA_NEW", new NewLZMACompressor(), (short)3);

		public final String name;
		public final Compressor compressor;
		public final short metadataID;

		/** cached values(). Never modify or pass this array to outside code! */
		private static final COMPRESSOR_TYPE[] values = values();

		COMPRESSOR_TYPE(String name, Compressor c, short metadataID) {
			this.name = name;
			this.compressor = c;
			this.metadataID = metadataID;
		}

		public static COMPRESSOR_TYPE getCompressorByMetadataID(short id) {
			for(COMPRESSOR_TYPE current : values)
				if(current.metadataID == id)
					return current;
			return null;
		}

		public static COMPRESSOR_TYPE getCompressorByName(String name) {
			for(COMPRESSOR_TYPE current : values)
				if(current.name.equals(name))
					return current;
			return null;
		}

		public static String getHelloCompressorDescriptor() {
			StringBuilder sb = new StringBuilder();
			sb.append(values.length);
			sb.append(" - ");
			getCompressorDescriptor(sb);
			return sb.toString();
		}

		public static String getCompressorDescriptor() {
			StringBuilder sb = new StringBuilder();
			getCompressorDescriptor(sb);
			return sb.toString();
		}

		public static void getCompressorDescriptor(StringBuilder sb) {
			boolean isfirst = true;
			for(COMPRESSOR_TYPE current : values) {
				if (isfirst)
					isfirst = false;
				else
					sb.append(", ");
				sb.append(current.name);
				sb.append('(');
				sb.append(current.metadataID);
				sb.append(')');
			}
		}

		/**
		 * make a COMPRESSOR_TYPE[] from a descriptor string<BR>
		 * the descriptor string is a comma separated list of numbers or names(can be mixed)<BR>
		 * it is better to store the string in db4o instead of the compressors?<BR>
		 * if the string is null/empty, it returns COMPRESSOR_TYPE.values() as default
		 * @param compressordescriptor
		 * @return
		 * @throws InvalidCompressionCodecException
		 */
		public static COMPRESSOR_TYPE[] getCompressorsArray(String compressordescriptor) throws InvalidCompressionCodecException {
			COMPRESSOR_TYPE[] result = getCompressorsArrayNoDefault(compressordescriptor);
			if (result == null) {
				COMPRESSOR_TYPE[] ret = new COMPRESSOR_TYPE[values.length-1];
				int x = 0;
				for(COMPRESSOR_TYPE v: values) {
					// LZMA should no longer be used. Use LZMA_NEW instead.
					if(v == LZMA) {
						logLzmaOldRemovedWarning();
						continue;
					}
					ret[x++] = v;
				}
				result = ret;
			}
			return result;
		}

		public static COMPRESSOR_TYPE[] getCompressorsArrayNoDefault(String compressordescriptor) throws InvalidCompressionCodecException {
			if (compressordescriptor == null)
				return null;
			if (compressordescriptor.trim().length() == 0)
				return null;
			String[] codecs = compressordescriptor.split(",");
			ArrayList<COMPRESSOR_TYPE> result = new ArrayList<COMPRESSOR_TYPE>(codecs.length);
			for (String codec : codecs) {
				codec = codec.trim();
				COMPRESSOR_TYPE ct = getCompressorByName(codec);
				if (ct == null) {
					try {
						ct = getCompressorByMetadataID(Short.parseShort(codec));
					} catch (NumberFormatException nfe) {
					}
				}
				if (ct == null) {
					throw new InvalidCompressionCodecException("Unknown compression codec identifier: '"+codec+"'");
				}
				if (result.contains(ct)) {
					throw new InvalidCompressionCodecException("Duplicate compression codec identifier: '"+codec+"'");
				}
				result.add(ct);
				if (result.contains(COMPRESSOR_TYPE.LZMA)) {
					// OldLZMA should no longer be used. Only accept it if it is the only codec in the list.
					Logger.warning(
							Compressor.class,
							"OldLZMA compression is buggy and no longer supported. It only exists to allow reinserting old keys.");
					if (result.size() > 1) {
						logLzmaOldRemovedWarning();
						result.remove(COMPRESSOR_TYPE.LZMA);
					}
				}
			}
			return result.toArray(new COMPRESSOR_TYPE[0]);
		}

		private static void logLzmaOldRemovedWarning() {
			Logger.warning(
					Compressor.class,
					"Codecs to choose contained ''LZMA'' along others. It was ignored. Please replace it with LZMA_NEW.");
		}

		@Override
		public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength)
				throws IOException, CompressionOutputSizeException {
			return compressor.compress(data, bf, maxReadLength, maxWriteLength);
		}

		@Override
		public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength)
				throws IOException, CompressionOutputSizeException {
			return compressor.compress(is, os, maxReadLength, maxWriteLength);
		}

		@Override
		public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength,
							 long amountOfDataToCheckCompressionRatio, int minimumCompressionPercentage)
				throws IOException, CompressionRatioException {
			return compressor.compress(is, os, maxReadLength, maxWriteLength, amountOfDataToCheckCompressionRatio, minimumCompressionPercentage);
		}

		@Override
		public long decompress(InputStream input, OutputStream output, long maxLength, long maxEstimateSizeLength) throws IOException, CompressionOutputSizeException {
			return compressor.decompress(input, output, maxLength, maxEstimateSizeLength);
		}

		@Override
		public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
			return compressor.decompress(dbuf, i, j, output);
		}

		public static int countCompressors() {
			return values.length;
		}

	}

	/**
	 * Compress the data.
	 * @param data The bucket to read from.
	 * @param bf The means to create a new bucket.
	 * @param maxReadLength The maximum number of bytes to read from the input bucket.
	 * @param maxWriteLength The maximum number of bytes to write to the output bucket. If this is exceeded, throw a CompressionOutputSizeException.
	 * @return The compressed data.
	 * @throws IOException If an error occurs reading or writing data.
	 * @throws CompressionOutputSizeException If the compressed data is larger than maxWriteLength.
	 */
	Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength)
			throws IOException, CompressionOutputSizeException;

	/**
	 * Compress the data.
	 * @param input The InputStream to read from.
	 * @param output The OutputStream to write to.
	 * @param maxReadLength The maximum number of bytes to read from the input bucket.
	 * @param maxWriteLength The maximum number of bytes to write to the output bucket. If this is exceeded, throw a CompressionOutputSizeException.
	 * @return The compressed data.
	 * @throws IOException If an error occurs reading or writing data.
	 * @throws CompressionOutputSizeException If the compressed data is larger than maxWriteLength.
	 */
	long compress(InputStream input, OutputStream output, long maxReadLength, long maxWriteLength)
			throws IOException, CompressionOutputSizeException;

	/**
	 * Compress the data (@see {@link #compress(InputStream, OutputStream, long, long)}) with checking of compression effect.
	 * @param amountOfDataToCheckCompressionRatio The data amount after compression of which we will check whether we have got the desired effect.
	 * @param minimumCompressionPercentage The minimal desired compression effect, %. A value of 0 means that the
	 *                                        compression effect will not be checked.
	 * @throws CompressionRatioException If the desired compression effect is not achieved.
	 */
	long compress(InputStream input, OutputStream output, long maxReadLength, long maxWriteLength,
				  long amountOfDataToCheckCompressionRatio, int minimumCompressionPercentage)
			throws IOException, CompressionRatioException;

	/**
	 * Decompress data.
	 * @param input Where to read the data to decompress from
	 * @param output Where to write the final product to
	 * @param maxLength The maximum length to decompress (we throw if more is present).
	 * @param maxEstimateSizeLength If the data is too big, and this is >0, read up to this many bytes in order to try to get the data size.
	 * @return Number of bytes copied
	 * @throws IOException
	 * @throws CompressionOutputSizeException
	 */
	long decompress(InputStream input, OutputStream output, long maxLength, long maxEstimateSizeLength) throws IOException, CompressionOutputSizeException;

	/** Decompress in RAM only.
	 * @param dbuf Input buffer.
	 * @param i Offset to start reading from.
	 * @param j Number of bytes to read.
	 * @param output Output buffer.
	 * @throws DecompressException
	 * @throws CompressionOutputSizeException
	 * @returns The number of bytes actually written.
	 */
	int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException;
}
