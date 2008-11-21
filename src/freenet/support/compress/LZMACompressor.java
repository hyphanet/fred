/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import SevenZip.Compression.LZMA.Decoder;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.CountedInputStream;
import freenet.support.io.CountedOutputStream;
import net.contrapunctus.lzma.LzmaInputStream;
import net.contrapunctus.lzma.LzmaOutputStream;

public class LZMACompressor implements Compressor {

	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		if(maxReadLength <= 0)
			throw new IllegalArgumentException();
		Bucket output = bf.makeBucket(maxWriteLength);
		InputStream is = null;
		OutputStream os = null;
		LzmaOutputStream lzmaOS = null;
		try {
			is = data.getInputStream();
			os = output.getOutputStream();
			CountedOutputStream cos = new CountedOutputStream(os);
			lzmaOS = new LzmaOutputStream(cos);
			long read = 0;
			// Bigger input buffer, so can compress all at once.
			// Won't hurt on I/O either, although most OSs will only return a page at a time.
			byte[] buffer = new byte[32768];
			while(true) {
				int l = (int) Math.min(buffer.length, maxReadLength - read);
				int x = l == 0 ? -1 : is.read(buffer, 0, buffer.length);
				if(x <= -1) break;
				if(x == 0) throw new IOException("Returned zero from read()");
				lzmaOS.write(buffer, 0, x);
				read += x;
				if(cos.written() > maxWriteLength)
					throw new CompressionOutputSizeException();
			}
			lzmaOS.flush();
			os = null;
		} finally {
			if(is != null) is.close();
			if(lzmaOS != null) lzmaOS.close();
			else if(os != null) os.close();
		}
		return output;
	}

	public Bucket decompress(Bucket data, BucketFactory bf, long maxLength, long maxCheckSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException {
		Bucket output;
		if(preferred != null)
			output = preferred;
		else
			output = bf.makeBucket(maxLength);
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Decompressing "+data+" size "+data.size()+" to new bucket "+output);
		CountedInputStream is = new CountedInputStream(new BufferedInputStream(data.getInputStream()));
		CountedOutputStream os = new CountedOutputStream(new BufferedOutputStream(output.getOutputStream()));
		decompress(is, os, maxLength, maxCheckSizeLength);
		os.close();
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Output: "+output+" size "+output.size()+" read "+is.count()+" written "+os.written());
		return output;
	}

	
	// Copied from DecoderThread
	// LICENSING: DecoderThread is LGPL 2.1/CPL according to comments.
	
    static final int propSize = 5;
    
    static final byte[] props = new byte[propSize];

    static {
        // enc.SetEndMarkerMode( true );
        // enc.SetDictionarySize( 1 << 20 );
        props[0] = 0x5d;
        props[1] = 0x00;
        props[2] = 0x00;
        props[3] = 0x10;
        props[4] = 0x00;
    }

	private void decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		Decoder decoder = new Decoder();
		decoder.SetDecoderProperties(props);
		decoder.Code(is, os, maxLength);
	}

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
