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

	public OutputStream getOutputStream() throws IOException {
		return proxy.getOutputStream();
	}

	public InputStream getInputStream() throws IOException {
		return proxy.getInputStream();
	}

	public String getName() {
		return proxy.getName();
	}

	public long size() {
		return proxy.size();
	}

	public boolean isReadOnly() {
		return proxy.isReadOnly();
	}

	public void setReadOnly() {
		proxy.setReadOnly();
	}

	public void free() {
		// Do nothing.
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	public Bucket createShadow() {
		return proxy.createShadow();
	}

}
