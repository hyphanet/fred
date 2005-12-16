package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.io.WritableToDataOutputStream;
import freenet.support.Fields;

/**
 * @author amphibian
 * 
 * Base class for node keys.
 */
public abstract class Key implements WritableToDataOutputStream {

    final int hash;
    double cachedNormalizedDouble;
    /** Whatever its type, it will need a routingKey ! */
    final byte[] routingKey;
    
    /** 32 bytes for hash, 2 bytes for type */
    public static final short KEY_SIZE_ON_DISK = 34;

    protected Key(byte[] routingKey) {
    	this.routingKey = routingKey;
    	hash = Fields.hashCode(routingKey);
        cachedNormalizedDouble = -1;
    }
    
    /**
     * Write to disk.
     * Take up exactly 22 bytes.
     * @param _index
     */
    public abstract void write(DataOutput _index) throws IOException;

    /**
     * Read a Key from a RandomAccessFile
     * @param raf The file to read from.
     * @return a Key, or throw an exception, or return null if the key is not parsable.
     */
    public static Key read(DataInput raf) throws IOException {
        short type = raf.readShort();
        if(type == NodeCHK.TYPE) {
            return NodeCHK.read(raf);
        }
        throw new IOException("Unrecognized format: "+type);
    }
    

    /**
     * Convert the key to a double between 0.0 and 1.0.
     * Normally we will hash the key first, in order to
     * make chosen-key attacks harder.
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
        int TYPE = getType();
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
    
    public abstract short getType();
    
    public int hashCode() {
        return hash;
    }
}
