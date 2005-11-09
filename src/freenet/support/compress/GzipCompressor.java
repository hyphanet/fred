package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import freenet.keys.CHKDecodeException;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

public class GzipCompressor extends Compressor {

	public Bucket compress(Bucket data, BucketFactory bf) throws IOException {
		Bucket output = bf.makeBucket(-1);
		InputStream is = data.getInputStream();
		OutputStream os = output.getOutputStream();
		GZIPOutputStream gos = new GZIPOutputStream(os);
		byte[] buffer = new byte[4096];
		while(true) {
			int x = is.read(buffer);
			if(x <= -1) break;
			if(x == 0) throw new IOException("Returned zero from read()");
			gos.write(buffer, 0, x);
		}
		is.close();
		gos.close();
		return output;
	}

	public Bucket decompress(Bucket data, BucketFactory bf) throws IOException {
		Bucket output = bf.makeBucket(-1);
		InputStream is = data.getInputStream();
		OutputStream os = output.getOutputStream();
		GZIPInputStream gis = new GZIPInputStream(is);
		byte[] buffer = new byte[4096];
		while(true) {
			int x = gis.read(buffer);
			if(x <= -1) break;
			if(x == 0) throw new IOException("Returned zero from read()");
			os.write(buffer, 0, x);
		}
		os.close();
		gis.close();
		return output;
	}

	public int decompress(byte[] dbuf, int i, int j, byte[] output) throws DecompressException {
        Inflater decompressor = new Inflater();
        decompressor.setInput(dbuf, i, j);
        try {
            int resultLength = decompressor.inflate(output);
            return resultLength;
        } catch (DataFormatException e) {
            throw new DecompressException("Invalid data: "+e);
        } catch (ArrayIndexOutOfBoundsException e) {
        	throw new DecompressException("Invalid data: "+e);
        }
	}

}
