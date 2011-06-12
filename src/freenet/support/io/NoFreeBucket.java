package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

public class NoFreeBucket implements Bucket {
	
	final Bucket proxy;
	
	public NoFreeBucket(Bucket orig) {
		proxy = orig;
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
	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	@Override
	public Bucket createShadow() {
		return proxy.createShadow();
	}

}
