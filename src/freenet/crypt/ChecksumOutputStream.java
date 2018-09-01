package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Checksum;

import freenet.support.Fields;

public class ChecksumOutputStream extends FilterOutputStream {
    
    final Checksum crc;
    private boolean closed;
    private final boolean writeChecksum;
    // FIXME unit test the skipping mechanism, or move it to another file?
    private final int skipPrefix;
    private int bytesInsidePrefix;

    public ChecksumOutputStream(OutputStream out, final Checksum crc, boolean writeChecksum, int skipPrefix) {
        super(out);
        this.crc = crc;
        this.writeChecksum = writeChecksum;
        this.skipPrefix = skipPrefix;
    }
    
    @Override
    public void write(int b) throws IOException {
        if(bytesInsidePrefix >= skipPrefix) {
            crc.update(b);
        } else bytesInsidePrefix++;
        out.write(b);
    }
    
    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        int chop = Math.min(skipPrefix - bytesInsidePrefix, length);
        if(chop <= 0) {
            // Finished writing prefix. Count everything later.
            crc.update(buf, offset, length);
            out.write(buf, offset, length);
        } else {
            if(length - chop > 0) {
                crc.update(buf, offset+chop, length-chop);
                bytesInsidePrefix = skipPrefix;
            } else {
                bytesInsidePrefix += length;
            }
            out.write(buf, offset, length);
        }
    }
    
    @Override
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }
    
    @Override
    public void close() throws IOException {
        if(writeChecksum) {
            synchronized(this) {
                if(closed) throw new IOException("Already closed");
                closed = true;
            }
            out.write(Fields.intToBytes((int)crc.getValue()));
        }
    }
    
    public long getValue() {
        return crc.getValue();
    }

}
