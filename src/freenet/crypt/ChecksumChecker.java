package freenet.crypt;

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
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

    public abstract byte[] generateChecksum(byte[] bufToChecksum, int offset, int length);

    public byte[] generateChecksum(byte[] bufToChecksum) {
        return generateChecksum(bufToChecksum, 0, bufToChecksum.length);
    }

    public abstract int getChecksumTypeID();
    
    // Checksum IDs.
    // FIXME use an enum when we are creating them from ID's.
    public static final int CHECKSUM_CRC = 1;

    /** Copy bytes from one stream to another, verifying and stripping the final checksum. 
     * @throws IOException 
     * @throws ChecksumFailedException */
    public abstract void copyAndStripChecksum(InputStream is, OutputStream os, long length) throws IOException, ChecksumFailedException;

    /** Read from disk and verify the checksum that follows the data. If it throws, the buffer will 
     * be zero'ed out. */
    public abstract void readAndChecksum(DataInput is, byte[] buf, int offset, int length) throws IOException, ChecksumFailedException;
    
    public void writeAndChecksum(OutputStream os, byte[] buf, int offset, int length) throws IOException {
        os.write(buf, offset, length);
        os.write(generateChecksum(buf, offset, length));
    }

    public void writeAndChecksum(ObjectOutputStream oos, byte[] buf) throws IOException {
        writeAndChecksum(oos, buf, 0, buf.length);
    }
    
}
