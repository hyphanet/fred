package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.client.async.StorageFormatException;
import freenet.support.api.Bucket;

public class NoFreeBucket implements Bucket, Serializable {
	
    private static final long serialVersionUID = 1L;
    final Bucket proxy;
	
	public NoFreeBucket(Bucket orig) {
		proxy = orig;
	}
	
	protected NoFreeBucket() {
	    // For serialization.
	    proxy = null;
	}

    @Override
	public OutputStream getOutputStream() throws IOException {
		return proxy.getOutputStream();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return proxy.getInputStream();
	}

	@Override
	public String getName() {
		return proxy.getName();
	}

	@Override
	public long size() {
		return proxy.size();
	}

	@Override
	public boolean isReadOnly() {
		return proxy.isReadOnly();
	}

	@Override
	public void setReadOnly() {
		proxy.setReadOnly();
	}

	@Override
	public void free() {
		// Do nothing.
	}

	@Override
	public Bucket createShadow() {
		return proxy.createShadow();
	}

    @Override
    public void onResume(ClientContext context) {
        proxy.onResume(context);
    }
    
    static final int MAGIC = 0xa88da5c2;

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        proxy.storeTo(dos);
    }

    protected NoFreeBucket(DataInputStream dis) throws IOException, StorageFormatException {
        proxy = BucketTools.restoreFrom(dis);
    }

}
