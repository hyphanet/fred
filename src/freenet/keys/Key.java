package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import freenet.io.WritableToDataOutputStream;

/**
 * @author amphibian
 * 
 * Base class for keys.
 */
public abstract class Key implements WritableToDataOutputStream {

    /** 20 bytes for hash, 2 bytes for type */
    public static final short KEY_SIZE_ON_DISK = 22;

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
        } else if(type == PublishStreamKey.TYPE) {
            return PublishStreamKey.read(raf);
        }
        throw new IOException("Unrecognized format: "+type);
    }
    
    /**
     * Convert the key to a double between 0.0 and 1.0.
     * Normally we will hash the key first, in order to
     * make chosen-key attacks harder.
     */
    public abstract double toNormalizedDouble();
}
