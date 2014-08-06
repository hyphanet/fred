package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;

/** Simple utility to check and write checksums. */
public abstract class ChecksumChecker {
    
    /** Get the length of the checksum */
    public abstract int checksumLength();
    
    /** Get an OutputStream that will write a checksum when closed. */
    public abstract OutputStream checksumWriter(OutputStream os);
    
    public abstract byte[] appendChecksum(byte[] data);
    
    /** Verify a checksum or throw */
    public void verifyChecksum(byte[] data, int offset, int length, byte[] checksum) throws ChecksumFailedException {
        if(!checkChecksum(data, offset, length, checksum)) throw new ChecksumFailedException();
    }
    
    /** Verify a checksum or report */
    public abstract boolean checkChecksum(byte[] data, int offset, int length, byte[] checksum);

    public abstract byte[] generateChecksum(byte[] bufToChecksum);

    public abstract int getChecksumTypeID();
    
    // Checksum IDs.
    // FIXME use an enum when we are creating them from ID's.
    public static final int CHECKSUM_CRC = 1;

    /** Copy bytes from one stream to another, verifying and stripping the final checksum. 
     * @throws IOException 
     * @throws ChecksumFailedException */
    public abstract void copyAndStripChecksum(InputStream is, OutputStream os, long length) throws IOException, ChecksumFailedException;

}
