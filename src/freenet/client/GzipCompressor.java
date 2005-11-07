package freenet.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

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
		gos.close();
		return output;
	}

}
