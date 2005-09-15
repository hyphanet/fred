package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class PublishStreamKey extends Key {

    public static final short TYPE = 0x0401;
    static final short ALGO_AES_PCFB_256 = 1;
    static final int KEY_LENGTH = 20;
    
    private final byte[] routingKey;
    private final int hashCode;
    private boolean hasSecureLongHashCode;
    private long secureLongHashCode;
    double cachedNormalizedDouble;
    
    public PublishStreamKey(byte[] routingKey) {
        this.routingKey = routingKey;
        hashCode = Fields.hashCode(routingKey);
    }

    public void write(DataOutput _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(routingKey);
    }

    public boolean equals(Object o) {
        if(o == this) return true;
        if(o instanceof PublishStreamKey) {
            PublishStreamKey k = (PublishStreamKey) o;
            return Arrays.equals(k.routingKey, routingKey);
        } else return false;
    }
    
    public int hashCode() {
        return hashCode;
    }
    
    public String toString() {
        return super.toString()+":"+HexUtil.bytesToHex(routingKey);
    }

    /**
     * @return A secure (given the length) 64-bit hash of
     * the key.
     */
    public synchronized long secureLongHashCode() {
        if(hasSecureLongHashCode) return secureLongHashCode;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            Logger.error(this, "No such algorithm: SHA-1 !!!!!", e);
            throw new RuntimeException(e);
        }
        md.update((byte)(TYPE >> 8));
        md.update((byte)TYPE);
        md.update(routingKey);
        byte[] digest = md.digest();
        secureLongHashCode = Fields.bytesToLong(digest);
        hasSecureLongHashCode = true;
        return secureLongHashCode;
    }

    public double toNormalizedDouble() {
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

    public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        stream.writeShort(TYPE);
        stream.write(routingKey);
    }
    
    public static Key read(DataInput raf, boolean readType) throws IOException {
        if(readType) {
            short type = raf.readShort();
            if(type != TYPE)
                // FIXME there has to be a better way to deal with format errors...
                throw new IOException("Invalid type");
        }
        byte[] buf = new byte[KEY_LENGTH];
        raf.readFully(buf);
        return new PublishStreamKey(buf);
    }
}
