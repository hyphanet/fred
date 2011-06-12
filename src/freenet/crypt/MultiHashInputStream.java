package freenet.crypt;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import freenet.support.Logger;

public class MultiHashInputStream extends FilterInputStream {

	// Bit flags for generateHashes
	private Digester[] digesters;
	private long readBytes;
	
	class Digester {
		HashType hashType;
		MessageDigest digest;
		
		Digester(HashType hashType) throws NoSuchAlgorithmException {
			this.hashType= hashType;
			digest = hashType.get();
		}
		
		HashResult getResult() {
			HashResult result = new HashResult(hashType, digest.digest());
			hashType.recycle(digest);
			digest = null;
			return result;
		}
	}
	
	public MultiHashInputStream(InputStream proxy, long generateHashes) {
		super(proxy);
		ArrayList<Digester> digesters = new ArrayList<Digester>();
		for(HashType type : HashType.values()) {
			if((generateHashes & type.bitmask) == type.bitmask) {
				try {
					digesters.add(new Digester(type));
				} catch (NoSuchAlgorithmException e) {
					Logger.error(this, "Algorithm not available: "+type);
				}
			}
		}
		this.digesters = digesters.toArray(new Digester[digesters.size()]);
	}
	
	@Override
	public int read(byte[] buf, int off, int len) throws IOException {
		int ret = in.read(buf, off, len);
		if(ret <= 0) return ret;
		for(Digester d : digesters)
			d.digest.update(buf, off, ret);
		readBytes += ret;
		return ret;
	}
	
	@Override
	public int read(byte[] buf) throws IOException {
		return read(buf, 0, buf.length);
	}

	/** Slow, you should buffer the stream to avoid this! */
	@Override
	public int read() throws IOException {
		int ret = in.read();
		if(ret < 0) return ret;
		byte[] b = new byte[] { (byte)ret };
		for(Digester d : digesters)
			d.digest.update(b, 0, 1);
		readBytes++;
		return ret;
	}
	
	@Override
	public long skip(long length) throws IOException {
		byte[] buf = new byte[(int)Math.min(32768, length)];
		long skipped = 0;
		while(length > 0) {
			int x = read(buf, 0, (int)Math.min(buf.length, length));
			if(x == -1) return skipped;
			skipped += x;
			length -= x;
		}
		return skipped;
	}
	
	public HashResult[] getResults() {
		HashResult[] results = new HashResult[digesters.length];
		for(int i=0;i<digesters.length;i++)
			results[i] = digesters[i].getResult();
		digesters = null;
		return results;
	}
	
	public long getReadBytes() {
		return readBytes;
	}
}
