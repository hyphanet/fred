package freenet.support.io;

import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/** Write to a temporary Bucket. On close, if not abort()'ed, write length and then copy the 
 * data. Does not close the underlying Bucket. */
public class PrependLengthOutputStream extends FilterOutputStream {
    
    private final Bucket temp;
    private final OutputStream origOS;
    private final int offset;
    private final boolean closeUnderlying;
    private boolean aborted;
    private boolean closed;

    /** Create a stream which writes to temporary space and then on a non-aborted close() will 
     * write the length (minus the offset) followed by the data. */
    public static PrependLengthOutputStream create(OutputStream out, BucketFactory bf, int offset, boolean closeUnderlying) throws IOException {
        Bucket temp = bf.makeBucket(-1);
        OutputStream os = temp.getOutputStream();
        return new PrependLengthOutputStream(os, temp, out, offset, closeUnderlying);
    }
    
    private PrependLengthOutputStream(OutputStream os, Bucket temp, OutputStream origOS, int offset, boolean closeUnderlying) {
        super(os);
        this.temp = temp;
        this.origOS = origOS;
        this.offset = offset;
        this.closeUnderlying = closeUnderlying;
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
    
    /** Abort the stream. Will write a length of 0 when close()'ed.
     * @return False if the stream has already been closed. */
    public boolean abort() throws IOException {
        if(closed) return false;
        aborted = true;
        return true;
    }
    
    @Override
    public void close() throws IOException {
        if(closed) return;
        out.close();
        DataOutputStream dos = new DataOutputStream(origOS);
        if(aborted) {
            dos.writeLong(0);
        } else {
            dos.writeLong(temp.size() - offset);
            BucketTools.copyTo(temp, dos, Long.MAX_VALUE);
        }
        temp.free();
        closed = true;
        if(closeUnderlying)
            dos.close();
    }

}
