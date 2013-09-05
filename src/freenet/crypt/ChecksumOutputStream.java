package freenet.crypt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Checksum;

public class ChecksumOutputStream extends FilterOutputStream {
    
    final Checksum crc;

    public ChecksumOutputStream(OutputStream out, final Checksum crc) {
        super(out);
        this.crc = crc;
    }
    
    public void write(int b) throws IOException {
        crc.update(b);
        out.write(b);
    }
    
    public void write(byte[] buf, int offset, int length) throws IOException {
        crc.update(buf, offset, length);
        out.write(buf, offset, length);
    }
    
    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }
    
    public long getValue() {
        return crc.getValue();
    }

}
