package freenet.crypt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Arrays;

import freenet.support.HexUtil;

public class HashResult implements Comparable<HashResult>, Cloneable, Serializable {

    private static final long serialVersionUID = 1L;
    /** The type of hash. */
	public final HashType type;
	/** The result of the hash. Immutable. */
	private final byte[] result;
	/** Cached HashType.values(). Never modify or pass this array to outside code! */
	private static final HashType[] HashType_values = HashType.values();
	
	public HashResult(HashType hashType, byte[] bs) {
		this.type = hashType;
		this.result = bs;
		assert(bs.length == type.hashLength);
	}
	
	protected HashResult() {
        // For serialization.
	    type = null;
	    result = null;
	}

	public static HashResult[] readHashes(DataInputStream dis) throws IOException {
		int bitmask = dis.readInt();
		if(bitmask == 0) return null;
		int count = 0;
		for(HashType h : HashType_values) {
			if((bitmask & h.bitmask) == h.bitmask) {
				count++;
			}
		}
		HashResult[] results = new HashResult[count];
		int x = 0;
		for(HashType h : HashType_values) {
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
	    if(hashes == null) hashes = new HashResult[0];
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

	public void writeTo(OutputStream dos) throws IOException {
		// Any given hash type has a fixed hash length, so just push the bytes.
		dos.write(result);
	}

	@Override
	public int compareTo(HashResult h) {
		if(type.bitmask == h.type.bitmask) return 0;
		if(type.bitmask > h.type.bitmask) return 1;
		/* else if(type.bitmask < h.type.bitmask) */ return -1;
	}

	public static long makeBitmask(HashResult[] hashes) {
		long l = 0;
		for(HashResult hash : hashes)
			l |= hash.type.bitmask;
		return l;
	}

	public static boolean strictEquals(HashResult[] results, HashResult[] hashes) {
		return Arrays.deepEquals(results, hashes);
	}

	public static boolean contains(HashResult[] hashes, HashType type) {
		for(HashResult res : hashes)
			if(res.type == type || type.name().equals(res.type.name()))
				return true;
		return false;
	}

	public static byte[] get(HashResult[] hashes, HashType type) {
		for(HashResult res : hashes)
			if(res.type == type || type.name().equals(res.type.name()))
				return res.result;
		return null;
	}

	public static HashResult[] copy(HashResult[] hashes) {
		if(hashes == null) return null;
		HashResult[] out = new HashResult[hashes.length];
		for(int i=0;i<hashes.length;i++) {
			out[i] = hashes[i].clone();
		}
		return out;
	}
	
	@Override
	public HashResult clone() {
		try {
			return (HashResult) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error(e);
		}
	}

	public String hashAsHex() {
		return HexUtil.bytesToHex(result);
	}

}
