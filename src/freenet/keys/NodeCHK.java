package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.support.Base64;
import freenet.support.Fields;

/**
 * @author amphibian
 * 
 * Node-level CHK. Does not have enough information to decode the payload.
 * But can verify that it is intact. Just has the routingKey.
 */
public class NodeCHK extends Key {

    final int hash;
    double cachedNormalizedDouble;
    
    public NodeCHK(byte[] routingKey2) {
        routingKey = routingKey2;
        if(routingKey2.length != KEY_LENGTH)
            throw new IllegalArgumentException("Wrong length: "+routingKey2.length+" should be "+KEY_LENGTH);
        hash = Fields.hashCode(routingKey);
        cachedNormalizedDouble = -1;
    }

    static final int KEY_LENGTH = 20;
    
    byte[] routingKey;
    public static final short TYPE = 0x0302;
    /** The size of the data */
	public static final int BLOCK_SIZE = 32768;

    public final void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        write(stream);
    }

    public String toString() {
        return super.toString() + "@"+Base64.encode(routingKey)+":"+Integer.toHexString(hash);
    }

    public final void write(DataOutput _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(routingKey);
    }
    
    public static Key read(DataInput raf) throws IOException {
        byte[] buf = new byte[KEY_LENGTH];
        raf.readFully(buf);
        return new NodeCHK(buf);
    }

    public int hashCode() {
        return hash;
    }

    public boolean equals(Object key) {
        if(key instanceof NodeCHK) {
            NodeCHK chk = (NodeCHK) key;
            return java.util.Arrays.equals(chk.routingKey, routingKey);
        }
        return false;
    }

    /**
     * @return The key, hashed, converted to a double in the range
     * 0.0 to 1.0.
     */
    public synchronized double toNormalizedDouble() {
        if(cachedNormalizedDouble > 0) return cachedNormalizedDouble;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        md.update(routingKey);
        md.update((byte)(TYPE >> 8));
        md.update((byte)TYPE);
        byte[] digest = md.digest();
        long asLong = Math.abs(Fields.bytesToLong(digest));
        // Math.abs can actually return negative...
        if(asLong == Long.MIN_VALUE)
            asLong = Long.MAX_VALUE;
        cachedNormalizedDouble = ((double)asLong)/((double)Long.MAX_VALUE);
        return cachedNormalizedDouble;
    }
}
