package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import freenet.support.Logger;

public class MultiHashOutputStream extends FilterOutputStream {

	// Bit flags for generateHashes
	private Digester[] digesters;
	
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
	
	public MultiHashOutputStream(OutputStream proxy, long generateHashes) {
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
	public void write(int arg0) throws java.io.IOException {
		out.write(arg0);
		for(Digester d : digesters)
			d.digest.update(new byte[] { (byte)arg0 });
	}

	@Override
	public void write(byte[] arg0) throws java.io.IOException {
		out.write(arg0);
		for(Digester d : digesters)
			d.digest.update(arg0);
	}
	
	@Override
	public void write(byte[] arg0, int arg1, int arg2) throws java.io.IOException {
		out.write(arg0, arg1, arg2);
		for(Digester d : digesters)
			d.digest.update(arg0, arg1, arg2);
	}
	
	public HashResult[] getResults() {
		HashResult[] results = new HashResult[digesters.length];
		for(int i=0;i<digesters.length;i++)
			results[i] = digesters[i].getResult();
		digesters = null;
		return results;
	}
}
