package freenet.crypt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.support.HexUtil;
import freenet.support.Logger;

public class HashResult implements Comparable<HashResult>, Cloneable {

	/** The type of hash. */
	public final HashType type;
	/** The result of the hash. Immutable. */
	private final byte[] result;
	
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

	@Override
	public int compareTo(HashResult h) {
		if(type.bitmask == h.type.bitmask) return 0;
		if(type.bitmask > h.type.bitmask) return 1;
		/* else if(type.bitmask < h.type.bitmask) */ return -1;
	}

	public void removeFrom(ObjectContainer container) {
		// HashType is an enum, so we don't need to worry about it.
		// Db4o does the right thing with them.
		container.delete(this);
	}

	public static long makeBitmask(HashResult[] hashes) {
		long l = 0;
		for(HashResult hash : hashes)
			l |= hash.type.bitmask;
		return l;
	}

	public static boolean strictEquals(HashResult[] results, HashResult[] hashes) {
		if(results.length != hashes.length) {
			Logger.error(HashResult.class, "Hashes not equal: "+results.length+" hashes vs "+hashes.length+" hashes");
			return false;
		}
		for(int i=0;i<results.length;i++) {
			if(results[i].type != hashes[i].type) {
				// FIXME Db4o kludge
				if(HashType.valueOf(results[i].type.name()) != HashType.valueOf(hashes[i].type.name())) {
					Logger.error(HashResult.class, "Hashes not the same type: "+results[i].type.name()+" vs "+hashes[i].type.name());
					return false;
				}
			}
			if(!Arrays.equals(results[i].result, hashes[i].result)) {
				Logger.error(HashResult.class, "Hash "+results[i].type.name()+" not equal");
				return false;
			}
		}
		return true;
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
