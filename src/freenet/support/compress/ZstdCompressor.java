/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.CountedOutputStream;

/**
 * {@link Compressor} using Zstandard (zstd) compression.
 *
 * Zstd provides compression ratios similar to LZMA but with much faster
 * compression and decompression speeds, typically 3-5x faster than LZMA
 * while achieving 10-15% better compression than gzip.
 */
public class ZstdCompressor extends AbstractCompressor {

	/** Default compression level (range 1-22, default 3 is a good balance) */
	private static final int COMPRESSION_LEVEL = 3;

	@Override
	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength)
			throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(maxWriteLength);
		InputStream is = null;
		OutputStream os = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			compress(is, os, maxReadLength, maxWriteLength);
			is.close(); is = null;
			os.close(); os = null;
		} finally {
			Closer.close(is);
			Closer.close(os);
		}
		return output;
	}

	@Override
	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength,
						 long amountOfDataToCheckCompressionRatio, int minimumCompressionPercentage)
			throws IOException, CompressionRatioException {
		if(maxReadLength < 0)
			throw new IllegalArgumentException();
		ZstdOutputStream zos = null;
		CountedOutputStream cos = new CountedOutputStream(os);
		try {
			zos = new ZstdOutputStream(cos, COMPRESSION_LEVEL);
			long read = 0;
			int bufferSize = 32768;
			byte[] buffer = new byte[bufferSize];
			long iterationToCheckCompressionRatio = amountOfDataToCheckCompressionRatio / bufferSize;
			int i = 0;
			while(true) {
				int l = (int) Math.min(buffer.length, maxReadLength - read);
				int x = l == 0 ? -1 : is.read(buffer, 0, l);
				if(x <= -1) break;
				if(x == 0) throw new IOException("Returned zero from read()");
				zos.write(buffer, 0, x);
				read += x;
				if(cos.written() > maxWriteLength)
					throw new CompressionOutputSizeException();

				if (++i == iterationToCheckCompressionRatio && minimumCompressionPercentage != 0) {
					checkCompressionEffect(read, cos.written(), minimumCompressionPercentage);
				}
			}
			zos.flush();
			zos.close();
			zos = null;
			cos.flush();
			if(cos.written() > maxWriteLength)
				throw new CompressionOutputSizeException();
			return cos.written();
		} finally {
			if(zos != null) {
				try {
					zos.close();
				} catch (IOException e) {
					// Ignore on cleanup
				}
			}
		}
	}

	@Override
	public long decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes)
			throws IOException, CompressionOutputSizeException {
		ZstdInputStream zis = new ZstdInputStream(is);
		long written = 0;
		int bufSize = 32768;
		if(maxLength > 0 && maxLength < bufSize)
			bufSize = (int)maxLength;
		byte[] buffer = new byte[bufSize];
		while(true) {
			int expectedBytesRead = (int) Math.min(buffer.length, maxLength - written);
			int bytesRead = zis.read(buffer, 0, buffer.length);
			if(expectedBytesRead < bytesRead) {
				Logger.normal(this, "expectedBytesRead="+expectedBytesRead+", bytesRead="+bytesRead+
						", written="+written+", maxLength="+maxLength+" throwing a CompressionOutputSizeException");
				if(maxCheckSizeBytes > 0) {
					written += bytesRead;
					while(true) {
						expectedBytesRead = (int) Math.min(buffer.length, maxLength + maxCheckSizeBytes - written);
						bytesRead = zis.read(buffer, 0, expectedBytesRead);
						if(bytesRead <= -1) throw new CompressionOutputSizeException(written);
						if(bytesRead == 0) throw new IOException("Returned zero from read()");
						written += bytesRead;
					}
				}
				throw new CompressionOutputSizeException();
			}
			if(bytesRead <= -1) {
				os.flush();
				return written;
			}
			if(bytesRead == 0) throw new IOException("Returned zero from read()");
			os.write(buffer, 0, bytesRead);
			written += bytesRead;
		}
	}

	@Override
	public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
		ByteArrayInputStream bais = new ByteArrayInputStream(dbuf, i, j);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(output.length);
		int bytes = 0;
		try {
			decompress(bais, baos, output.length, -1);
			bytes = baos.size();
		} catch (IOException e) {
			throw new Error("Got IOException: " + e.getMessage(), e);
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}
}
