package freenet.client.async;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import freenet.support.api.Bucket;

public class ReadBucketAndFreeInputStream extends FilterInputStream {
    
    private final Bucket data;

    public static InputStream create(Bucket data) throws IOException {
        return new ReadBucketAndFreeInputStream(data.getInputStream(), data);
    }
    
    private ReadBucketAndFreeInputStream(InputStream in, Bucket data) {
        super(in);
        this.data = data;
    }
    
    @Override
    public int read(byte[] buf, int offset, int length) throws IOException {
        // Necessary for efficiency, FilterInputStream pipes everything through "int read()".
        return in.read(buf, offset, length);
    }
    
    @Override
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }
    
    @Override
    public void close() throws IOException {
        in.close();
        data.free();
    }

}
