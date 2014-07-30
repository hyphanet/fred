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

    public ChecksumOutputStream(OutputStream out, final Checksum crc, boolean writeChecksum) {
        super(out);
        this.crc = crc;
        this.writeChecksum = writeChecksum;
    }
    
    @Override
    public void write(int b) throws IOException {
        crc.update(b);
        out.write(b);
    }
    
    @Override
    public void write(byte[] buf, int offset, int length) throws IOException {
        crc.update(buf, offset, length);
        out.write(buf, offset, length);
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
        out.close();
    }
    
    public long getValue() {
        return crc.getValue();
    }

}
