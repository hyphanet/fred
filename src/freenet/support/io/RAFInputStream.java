package freenet.support.io;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import freenet.support.api.RandomAccessBuffer;

public class RAFInputStream extends InputStream {
    
    private final RandomAccessBuffer underlying;
    private final long rafLength;

    private long rafOffset;

    public RAFInputStream(RandomAccessBuffer data, long offset, long size) {
        this.underlying = data;
        this.rafOffset = offset;
        this.rafLength = size;
    }

    @Override
    public int read() throws IOException {
        byte[] buf = new byte[1];
        int length = read(buf, 0, 1);
        if (length > 0) {
            return Byte.toUnsignedInt(buf[0]);
        }
        return -1;
    }
    
    @Override
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }
    
    @Override
    public int read(byte[] buf, int offset, int length) throws IOException {
        if(rafOffset >= rafLength) throw new EOFException();
        length = (int) Math.min((long)length, rafLength-rafOffset);
        underlying.pread(rafOffset, buf, offset, length);
        rafOffset += length;
        return length;
    }

}
