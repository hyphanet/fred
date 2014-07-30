package freenet.crypt;

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

}
