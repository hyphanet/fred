package freenet.crypt;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import freenet.client.async.ReadBucketAndFreeInputStream;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.PrependLengthOutputStream;

/** Simple utility to check and write checksums. */
public abstract class ChecksumChecker {
    
    /** Get the length of the checksum */
    public abstract int checksumLength();
    
    /** Get an OutputStream that will write a checksum when closed. It will not close the 
     * underlying stream. */
    public abstract OutputStream checksumWriter(OutputStream os);

    /** Wraps a stream wrapping a PrependLengthOutputStream, providing a way to get to the abort()
     * on the PrependLengthOutputStream. */
    public class AbortableChecksummedOutputStream extends FilterOutputStream {
        public AbortableChecksummedOutputStream(OutputStream out, PrependLengthOutputStream underlying) {
            super(out);
            this.underlying = underlying;
        }
        private final PrependLengthOutputStream underlying;
        public void abort() throws IOException {
            underlying.abort();
        }
        @Override
        public void write(byte[] buf, int offset, int length) throws IOException {
            // Unfortunately this is necessary because FilterOutputStream passes everything through write(int).
            out.write(buf, offset, length);
        }
        
        @Override
        public void write(byte[] buf) throws IOException {
            write(buf, 0, buf.length);
        }
    }
    
    /** Get an OutputStream that will write to a temporary Bucket, append a checksum and prepend a
     * length.
     * @param os The underlying stream, which will not be closed.
     * @param bf Used to allocate temporary storage.
     * @throws IOException 
     */
    public AbortableChecksummedOutputStream checksumWriterWithLength(final OutputStream dos, BucketFactory bf) throws IOException {
        PrependLengthOutputStream lengthAdder = PrependLengthOutputStream.create(dos, bf, checksumLength());
        return new AbortableChecksummedOutputStream(checksumWriter(lengthAdder), lengthAdder);
    }
    
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
    
    public InputStream checksumReaderWithLength(InputStream dis, BucketFactory bf, long maxLength) throws IOException, ChecksumFailedException {
        // IMHO it is better to implement this with copying, because then we don't start 
        // constructing objects from bad data...
        long length = new DataInputStream(dis).readLong();
        if(length < 0 || length > maxLength) throw new IOException("Bad length");
        final Bucket bucket = bf.makeBucket(-1);
        OutputStream os = bucket.getOutputStream();
        copyAndStripChecksum(dis, os, length);
        os.close();
        return ReadBucketAndFreeInputStream.create(bucket);
    }
    
    public void writeAndChecksum(OutputStream os, byte[] buf, int offset, int length) throws IOException {
        os.write(buf, offset, length);
        os.write(generateChecksum(buf, offset, length));
    }

    public void writeAndChecksum(ObjectOutputStream oos, byte[] buf) throws IOException {
        writeAndChecksum(oos, buf, 0, buf.length);
    }
    
}
