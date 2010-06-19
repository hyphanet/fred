package freenet.support.compress;

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

	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(maxWriteLength);
		InputStream is = null;
		OutputStream os = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			compress(is, os, maxReadLength, maxWriteLength);
		} finally {
			if(is != null) is.close();
			if(os != null) os.close();
		}
		return output;
	}
	
	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		if(maxReadLength < 0)
			throw new IllegalArgumentException();
		GZIPOutputStream gos = null;
		os = new BufferedOutputStream(os);
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
			return cos.written();
		} finally {
			if(gos != null) {
				gos.flush();
				gos.finish();
			}
		}
	}

	public Bucket decompress(Bucket data, BucketFactory bf, long maxLength, long maxCheckSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException {
		Bucket output;
		if(preferred != null)
			output = preferred;
		else
			output = bf.makeBucket(maxLength);
		InputStream is = null;
		OutputStream os = null;
		try {
		is = data.getInputStream();
		os = output.getOutputStream();
		decompress(is, os, maxLength, maxCheckSizeLength);
		os.close(); os = null;
		is.close(); is = null;
		return output;
		} finally {
			if(is != null) Closer.close(is);
			if(os != null) Closer.close(os);
		}
	}

	private long decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		GZIPInputStream gis = new GZIPInputStream(is);
		long written = 0;
		byte[] buffer = new byte[4096];
		while(true) {
			int l = (int) Math.min(buffer.length, maxLength - written);
			// We can over-read to determine whether we have over-read.
			// We enforce maximum size this way.
			// FIXME there is probably a better way to do this!
			int x = gis.read(buffer, 0, buffer.length);
			if(l < x) {
				Logger.normal(this, "l="+l+", x="+x+", written="+written+", maxLength="+maxLength+" throwing a CompressionOutputSizeException");
				if(maxCheckSizeBytes > 0) {
					written += x;
					while(true) {
						l = (int) Math.min(buffer.length, maxLength + maxCheckSizeBytes - written);
						x = gis.read(buffer, 0, l);
						if(x <= -1) throw new CompressionOutputSizeException(written);
						if(x == 0) throw new IOException("Returned zero from read()");
						written += x;
					}
				}
				throw new CompressionOutputSizeException();
			}
			if(x <= -1) return written;
			if(x == 0) throw new IOException("Returned zero from read()");
			os.write(buffer, 0, x);
			written += x;
		}
	}

	public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
		// Didn't work with Inflater.
		// FIXME fix sometimes to use Inflater - format issue?
		ByteArrayInputStream bais = new ByteArrayInputStream(dbuf, i, j);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(output.length);
		int bytes = 0;
		try {
			bytes = (int)decompress(bais, baos, output.length, -1);
		} catch (IOException e) {
			// Impossible
			throw new Error("Got IOException: " + e.getMessage(), e);
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}
}
