package freenet.support.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import freenet.support.Bucket;
import freenet.support.BucketFactory;

public class GzipCompressor extends Compressor {

	public Bucket compress(Bucket data, BucketFactory bf, long maxLength) throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(-1);
		InputStream is = null;
		OutputStream os = null;
		GZIPOutputStream gos = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			gos = new GZIPOutputStream(os);
			long written = 0;
			byte[] buffer = new byte[4096];
			while(true) {
				int l = (int) Math.min(buffer.length, maxLength - written);
				int x = is.read(buffer, 0, buffer.length);
				if(l < x) {
					throw new CompressionOutputSizeException();
				}
				if(x <= -1) break;
				if(x == 0) throw new IOException("Returned zero from read()");
				gos.write(buffer, 0, x);
				written += x;
			}
			gos.flush();
		} finally {
			if(is != null) is.close();
			if(gos != null) gos.close();
			else if(os != null) os.close();
		}
		return output;
	}

	public Bucket decompress(Bucket data, BucketFactory bf, long maxLength) throws IOException, CompressionOutputSizeException {
		Bucket output = bf.makeBucket(-1);
		InputStream is = data.getInputStream();
		OutputStream os = output.getOutputStream();
		decompress(is, os, maxLength);
		os.close();
		is.close();
		return output;
	}

	private long decompress(InputStream is, OutputStream os, long maxLength) throws IOException, CompressionOutputSizeException {
		GZIPInputStream gis = new GZIPInputStream(is);
		long written = 0;
		byte[] buffer = new byte[4096];
		while(true) {
			int l = (int) Math.min(buffer.length, maxLength - written);
			int x = gis.read(buffer, 0, 4096);
			if(l < x) {
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
			bytes = (int)decompress(bais, baos, output.length);
		} catch (IOException e) {
			throw new Error("Got IOException: "+e.getMessage());
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}

}
