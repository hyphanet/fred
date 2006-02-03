package freenet.support;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * FIXME: implement a hash verifying version of this.
 */
public class ReadOnlyFileSliceBucket implements Bucket {

	private final File file;
	private final long startAt;
	private final long length;
	
	public ReadOnlyFileSliceBucket(File f, long startAt, long length) {
		this.file = f;
		this.startAt = startAt;
		this.length = length;
	}
	
	public OutputStream getOutputStream() throws IOException {
		throw new IOException("Bucket is read-only");
	}

	public InputStream getInputStream() throws IOException {
		return new MyInputStream();
	}

	public String getName() {
		return "ROFS:"+file.getAbsolutePath()+":"+startAt+":"+length;
	}

	public long size() {
		return length;
	}

	public boolean isReadOnly() {
		return true;
	}

	public void setReadOnly() {
		// Do nothing
	}

	class MyInputStream extends InputStream {

		private RandomAccessFile f;
		private long ptr; // relative to startAt
		
		MyInputStream() throws IOException {
			try {
				this.f = new RandomAccessFile(file,"r");
				f.seek(startAt);
				if(f.length() < (startAt+length))
					throw new ReadOnlyFileSliceBucketException("File truncated? Length "+f.length()+" but start at "+startAt+" for "+length+" bytes");
				ptr = 0;
			} catch (FileNotFoundException e) {
				throw new ReadOnlyFileSliceBucketException(e);
			}
		}
		
		public int read() throws IOException {
			if(ptr > length)
				throw new EOFException();
			int x = f.read();
			ptr++;
			return x;
		}
		
		public int read(byte[] buf, int offset, int len) throws IOException {
			if(ptr > length)
				throw new EOFException();
			len = (int) Math.min(len, length - ptr);
			int x = f.read(buf, offset, len);
			ptr += x;
			return x;
		}
		
		public int read(byte[] buf) throws IOException {
			return read(buf, 0, buf.length);
		}

	}

	public class ReadOnlyFileSliceBucketException extends IOException {

		public ReadOnlyFileSliceBucketException(FileNotFoundException e) {
			super("File not found: "+e.getMessage());
			initCause(e);
		}

		public ReadOnlyFileSliceBucketException(String string) {
			super(string);
		}
		
	}

	public void free() {
		// Do nothing
	}
	
}
