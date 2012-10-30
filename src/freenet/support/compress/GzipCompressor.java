package freenet.support.compress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.CountedOutputStream;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class GzipCompressor implements Compressor {

	@Override
	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(maxWriteLength);
		InputStream is = null;
		OutputStream os = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			compress(is, os, maxReadLength, maxWriteLength);
			// It is essential that the close()'s throw if there is any problem.
			is.close(); is = null;
			os.close(); os = null;
		} finally {
			Closer.close(is);
			Closer.close(os);
		}
		return output;
	}
	
	@Override
	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		if(maxReadLength < 0)
			throw new IllegalArgumentException();
		is = new BufferedInputStream(is, 32768);
		GZIPOutputStream gos = null;
		os = new BufferedOutputStream(os, 32768);
		CountedOutputStream cos = new CountedOutputStream(os);
		try {
			gos = new GZIPOutputStream(cos);
			long read = 0;
			// Bigger input buffer, so can compress all at once.
			// Won't hurt on I/O either, although most OSs will only return a page at a time.
			byte[] buffer = new byte[32768];
			while(true) {
				int l = (int) Math.min(buffer.length, maxReadLength - read);
				int x = l == 0 ? -1 : is.read(buffer, 0, l);
				if(x <= -1) break;
				if(x == 0) throw new IOException("Returned zero from read()");
				gos.write(buffer, 0, x);
				read += x;
				if(cos.written() > maxWriteLength)
					throw new CompressionOutputSizeException();
			}
			gos.flush();
			gos.finish();
			cos.flush();
			gos = null;
			if(cos.written() > maxWriteLength)
				throw new CompressionOutputSizeException();
			return cos.written();
		} finally {
			if(gos != null) {
				gos.flush();
				gos.finish();
			}
		}
	}

	@Override
	public long decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(is, 32768));
		os = new BufferedOutputStream(os, 32768);
		long written = 0;
		int bufSize = 32768;
		if(maxLength > 0 && maxLength < bufSize)
			bufSize = (int)maxLength;
		byte[] buffer = new byte[bufSize];
		while(true) {
			int expectedBytesRead = (int) Math.min(buffer.length, maxLength - written);
			// We can over-read to determine whether we have over-read.
			// We enforce maximum size this way.
			// FIXME there is probably a better way to do this!
			int bytesRead = gis.read(buffer, 0, buffer.length);
			if(expectedBytesRead < bytesRead) {
				Logger.normal(this, "expectedBytesRead="+expectedBytesRead+", bytesRead="+bytesRead+", written="+written+", maxLength="+maxLength+" throwing a CompressionOutputSizeException");
				if(maxCheckSizeBytes > 0) {
					written += bytesRead;
					while(true) {
						expectedBytesRead = (int) Math.min(buffer.length, maxLength + maxCheckSizeBytes - written);
						bytesRead = gis.read(buffer, 0, expectedBytesRead);
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
		// Didn't work with Inflater.
		// FIXME fix sometimes to use Inflater - format issue?
		ByteArrayInputStream bais = new ByteArrayInputStream(dbuf, i, j);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(output.length);
		int bytes = 0;
		try {
			decompress(bais, baos, output.length, -1);
			bytes = baos.size();
		} catch (IOException e) {
			// Impossible
			throw new Error("Got IOException: " + e.getMessage(), e);
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}
}
