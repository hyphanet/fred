/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A data compressor. Contains methods to get all data compressors.
 * This is for single-file compression (gzip, bzip2) as opposed to archives.
 */
public interface Compressor {
	
	public static final String DEFAULT_COMPRESSORDESCRIPTOR = null;

	public enum COMPRESSOR_TYPE implements Compressor {
		// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
		// They will be tried in order: put the less resource consuming first
		GZIP("GZIP", new GzipCompressor(), (short) 0),
		BZIP2("BZIP2", new Bzip2Compressor(), (short) 1),
		LZMA("LZMA", new OldLZMACompressor(), (short)2);

		public final String name;
		public final Compressor compressor;
		public final short metadataID;

		COMPRESSOR_TYPE(String name, Compressor c, short metadataID) {
			this.name = name;
			this.compressor = c;
			this.metadataID = metadataID;
		}

		public static COMPRESSOR_TYPE getCompressorByMetadataID(short id) {
			COMPRESSOR_TYPE[] values = values();
			for(COMPRESSOR_TYPE current : values)
				if(current.metadataID == id)
					return current;
			return null;
		}

		public static COMPRESSOR_TYPE getCompressorByName(String name) {
			COMPRESSOR_TYPE[] values = values();
			for(COMPRESSOR_TYPE current : values)
				if(current.name.equals(name))
					return current;
			return null;
		}

		public static String getHelloCompressorDescriptor() {
			StringBuilder sb = new StringBuilder();
			sb.append(COMPRESSOR_TYPE.values().length);
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
			COMPRESSOR_TYPE[] values = values();
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
			if (result == null)
				return COMPRESSOR_TYPE.values();
			return result;
		}

		public static COMPRESSOR_TYPE[] getCompressorsArrayNoDefault(String compressordescriptor) throws InvalidCompressionCodecException {
			if (compressordescriptor == null)
				return null;
			if (compressordescriptor.trim().length() == 0)
				return null;
			String[] codecs = compressordescriptor.split(",");
			Vector<COMPRESSOR_TYPE> result = new Vector<COMPRESSOR_TYPE>();
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
			}
			return result.toArray(new COMPRESSOR_TYPE[result.size()]);
		}

		public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
			if(compressor == null) {
				// DB4O VOODOO! See below.
				if(name != null) return getOfficial().compress(data, bf, maxReadLength, maxWriteLength);
			}
			return compressor.compress(data, bf, maxReadLength, maxWriteLength);
		}

		public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
			if(compressor == null) {
				// DB4O VOODOO! See below.
				if(name != null) return getOfficial().compress(is, os, maxReadLength, maxWriteLength);
			}
			return compressor.compress(is, os, maxReadLength, maxWriteLength);
		}

		public Bucket decompress(Bucket data, BucketFactory bucketFactory, long maxLength, long maxEstimateSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException {
			if(compressor == null) {
				// DB4O VOODOO! See below.
				if(name != null) return getOfficial().decompress(data, bucketFactory, maxLength, maxEstimateSizeLength, preferred);
			}
			return compressor.decompress(data, bucketFactory, maxLength, maxEstimateSizeLength, preferred);
		}

		public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
			if(compressor == null) {
				// DB4O VOODOO! See below.
				if(name != null) return getOfficial().decompress(dbuf, i, j, output);
			}
			return compressor.decompress(dbuf, i, j, output);
		}

		// DB4O VOODOO!
		// Copies of the static fields get stored into the database.
		// Really the solution is probably to store the codes only.

		private Compressor getOfficial() {
			if(name.equals("GZIP")) return GZIP;
			if(name.equals("BZIP2")) return BZIP2;
			if(name.equals("LZMA")) return LZMA;
			return null;
		}

		public boolean objectCanDeactivate(ObjectContainer container) {
			// Do not deactivate the official COMPRESSOR_TYPE's.
			if(isOfficial()) return false;
			return true;
		}

		public boolean objectCanActivate(ObjectContainer container) {
			// Do not activate the official COMPRESSOR_TYPE's.
			if(isOfficial()) return false;
			return true;
		}

		public boolean isOfficial() {
			return this == GZIP || this == BZIP2 || this == LZMA;
		}

		public static int countCompressors() {
			return Compressor.COMPRESSOR_TYPE.values().length;
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
	public abstract Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException;

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
	public abstract long compress(InputStream input, OutputStream output, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException;

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
