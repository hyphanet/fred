package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author amphibian
 * 
 * Base class for keys.
 */
public abstract class Key {

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
        }
        throw new IOException("Unrecognized format: "+type);
    }
    
    public abstract int hashCode();
}
