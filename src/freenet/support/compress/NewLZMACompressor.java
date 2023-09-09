/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import SevenZip.Compression.LZMA.Decoder;
import SevenZip.Compression.LZMA.Encoder;
import SevenZip.ICodeProgress;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.CountedInputStream;
import freenet.support.io.CountedOutputStream;

public class NewLZMACompressor extends AbstractCompressor {

	// Dictionary size 1MB, this is equivalent to lzma -4, it uses 16MB to compress and 2MB to decompress.
	// Next one up is 2MB = -5 = 26M compress, 3M decompress.
	static final int MAX_DICTIONARY_SIZE = 1<<20;

	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	// Copied from EncoderThread. See below re licensing.
	@Override
	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException {
		Bucket output = bf.makeBucket(maxWriteLength);
		try (
			InputStream is = data.getInputStream();
			OutputStream os = output.getOutputStream()
		) {
			if(logMINOR) {
				Logger.minor(this, "Compressing "+data+" size "+data.size()+" to new bucket "+output);
			}
			compress(is, os, maxReadLength, maxWriteLength);
		}
		return output;
	}

	@Override
	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength,
						 final long amountOfDataToCheckCompressionRatio, final int minimumCompressionPercentage)
			throws IOException, CompressionRatioException {
		CountedInputStream cis = null;
		CountedOutputStream cos = null;
		cis = new CountedInputStream(is);
		cos = new CountedOutputStream(os);
		Encoder encoder = new Encoder();
		encoder.SetEndMarkerMode( true );
		int dictionarySize = 1;
		if(maxReadLength == Long.MAX_VALUE || maxReadLength < 0) {
			dictionarySize = MAX_DICTIONARY_SIZE;
			Logger.error(this, "No indication of size, having to use maximum dictionary size", new Exception("debug"));
		} else {
			while(dictionarySize < maxReadLength && dictionarySize < MAX_DICTIONARY_SIZE)
				dictionarySize <<= 1;
		}
		encoder.SetDictionarySize( dictionarySize );
		encoder.WriteCoderProperties(os);
		try {
			encoder.Code(cis, cos, maxReadLength, maxWriteLength, new ICodeProgress() {
				boolean compressionEffectShouldBeChecked = minimumCompressionPercentage != 0;

				@Override
				public void SetProgress(long processedInSize, long processedOutSize) {
					if (compressionEffectShouldBeChecked && processedInSize > amountOfDataToCheckCompressionRatio) {
						try {
							checkCompressionEffect(processedInSize, processedOutSize, minimumCompressionPercentage);
						} catch (CompressionRatioException e) {
							throw new RuntimeException(e); // need to escape from foreign API :-(
						}
						compressionEffectShouldBeChecked = false;
					}
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof CompressionRatioException) {
				throw (CompressionRatioException) e.getCause();
			} else {
				throw e;
			}
		}
		if(cos.written() > maxWriteLength)
			throw new CompressionOutputSizeException(cos.written());
		cos.flush();
		if(logMINOR)
			Logger.minor(this, "Read "+cis.count()+" written "+cos.written());
		return cos.written();
	}

	public Bucket decompress(Bucket data, BucketFactory bf, long maxLength, long maxCheckSizeLength, Bucket preferred) throws IOException {
		Bucket output;
		if(preferred != null) {
			output = preferred;
		} else {
			output = bf.makeBucket(maxLength);
		}
		if(logMINOR) {
			Logger.minor(this, "Decompressing "+data+" size "+data.size()+" to new bucket "+output);
		}
		try (
			CountedInputStream is = new CountedInputStream(data.getInputStream());
			OutputStream os = output.getOutputStream();
		) {
			decompress(is, os, maxLength, maxCheckSizeLength);
			if (logMINOR) {
				Logger.minor(this, "Output: "+output+" size "+output.size()+" read "+is.count());
			}
		}
		return output;
	}

	@Override
	public long decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		byte[] props = new byte[5];
		DataInputStream dis = new DataInputStream(is);
		dis.readFully(props);
		CountedOutputStream cos = new CountedOutputStream(os);

		int dictionarySize = 0;
		for (int i = 0; i < 4; i++)
			dictionarySize += ((props[1 + i]) & 0xFF) << (i * 8);

		if(dictionarySize < 0) throw new InvalidCompressedDataException("Invalid dictionary size");
		if(dictionarySize > MAX_DICTIONARY_SIZE) throw new TooBigDictionaryException();
		Decoder decoder = new Decoder();
		if(!decoder.SetDecoderProperties(props)) throw new InvalidCompressedDataException("Invalid properties");
		decoder.Code(is, cos, maxLength);
		//cos.flush();
		return cos.written();
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
