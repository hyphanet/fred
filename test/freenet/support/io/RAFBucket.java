package freenet.support.io;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.client.async.ClientContext;
import freenet.support.api.Bucket;

public class RAFBucket implements Bucket {
    
    private final LockableRandomAccessThing underlying;
    final long size;

    public RAFBucket(LockableRandomAccessThing underlying) throws IOException {
        this.underlying = underlying;
        size = underlying.size();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new IOException("Not supported");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new RAFInputStream(underlying, 0, underlying.size());
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public void setReadOnly() {
        // Ignore.
    }

    @Override
    public void free() {
        underlying.free();
    }

    @Override
    public Bucket createShadow() {
        return null;
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        throw new UnsupportedOperationException();
    }

}
