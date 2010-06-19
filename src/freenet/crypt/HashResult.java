package freenet.crypt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class HashResult implements Comparable {

	public final HashType type;
	public final byte[] result;
	
	public HashResult(HashType hashType, byte[] bs) {
		this.type = hashType;
		this.result = bs;
		assert(bs.length == type.hashLength);
	}

	public static HashResult[] readHashes(DataInputStream dis) throws IOException {
		int bitmask = dis.readInt();
		int count = 0;
		for(HashType h : HashType.values()) {
			if((bitmask & h.bitmask) == h.bitmask) {
				count++;
			}
		}
		HashResult[] results = new HashResult[count];
		int x = 0;
		for(HashType h : HashType.values()) {
			if((bitmask & h.bitmask) == h.bitmask) {
				results[x++] = HashResult.readFrom(h, dis);
			}
		}
		return results;
	}

	private static HashResult readFrom(HashType h, DataInputStream dis) throws IOException {
		byte[] buf = new byte[h.hashLength];
		dis.readFully(buf);
		return new HashResult(h, buf);
	}

	public static void write(HashResult[] hashes, DataOutputStream dos) throws IOException {
		int bitmask = 0;
		for(HashResult hash : hashes)
			bitmask |= hash.type.bitmask;
		dos.writeInt(bitmask);
		Arrays.sort(hashes);
		HashType prev = null;
		for(HashResult h : hashes) {
			if(prev == h.type || (prev != null && prev.bitmask == h.type.bitmask)) throw new IllegalArgumentException("Multiple hashes of the same type!");
			prev = h.type;
		}
		for(HashResult h : hashes)
			h.writeTo(dos);
	}

	private void writeTo(DataOutputStream dos) throws IOException {
		// Any given hash type has a fixed hash length, so just push the bytes.
		dos.write(result);
	}

	public int compareTo(Object arg0) {
		HashResult h = (HashResult)arg0;
		if(type.bitmask == h.type.bitmask) return 0;
		if(type.bitmask > h.type.bitmask) return 1;
		/* else if(type.bitmask < h.type.bitmask) */ return -1;
	}

}
