/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

import com.db4o.ObjectContainer;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * FIXME: implement a hash verifying version of this.
 */
public class ReadOnlyFileSliceBucket implements Bucket, SerializableToFieldSetBucket {

	private final File file;
	private final long startAt;
	private final long length;

	/**
	 *
	 * @param f
	 * @param startAt
	 * @param length
	 */
	public ReadOnlyFileSliceBucket(File f, long startAt, long length) {
		this.file = new File(f.getPath()); // copy so we can delete it
		this.startAt = startAt;
		this.length = length;
	}

	/**
	 *
	 * @param fs
	 * @throws CannotCreateFromFieldSetException
	 */
	public ReadOnlyFileSliceBucket(SimpleFieldSet fs) throws CannotCreateFromFieldSetException {
		String tmp = fs.get("Filename");
		if(tmp == null) {
			throw new CannotCreateFromFieldSetException("No filename");
		}
		this.file = new File(tmp);
		tmp = fs.get("Length");
		if(tmp == null) {
			throw new CannotCreateFromFieldSetException("No length");
		}
		try {
			length = Long.parseLong(tmp);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt length " + tmp, e);
		}
		tmp = fs.get("Offset");
		if(tmp == null) {
			throw new CannotCreateFromFieldSetException("No offset");
		}
		try {
			startAt = Long.parseLong(tmp);
		} catch (NumberFormatException e) {
			throw new CannotCreateFromFieldSetException("Corrupt offset " + tmp, e);
		}
	}

	public OutputStream getOutputStream() throws IOException {
		throw new IOException("Bucket is read-only");
	}

	public InputStream getInputStream() throws IOException {
		return new MyInputStream();
	}

	public String getName() {
		return "ROFS:" + file.getAbsolutePath() + ':' + startAt + ':' + length;
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

	private class MyInputStream extends InputStream {

		private RandomAccessFile f;
		private long ptr; // relative to startAt

		MyInputStream() throws IOException {
			try {
				this.f = new RandomAccessFile(file, "r");
				f.seek(startAt);
				if(f.length() < (startAt + length)) {
					throw new ReadOnlyFileSliceBucketException("File truncated? Length " + f.length() + " but start at " + startAt + " for " + length + " bytes");
				}
				ptr = 0;
			} catch (FileNotFoundException e) {
				throw new ReadOnlyFileSliceBucketException(e);
			}
		}

		@Override
		public int read() throws IOException {
			if(ptr >= length) {
				return -1;
			}
			int x = f.read();
			if(x != -1) {
				ptr++;
			}
			return x;
		}

		@Override
		public int read(byte[] buf, int offset, int len) throws IOException {
			if(ptr >= length) {
				return -1;
			}
			len = (int) Math.min(len, length - ptr);
			int x = f.read(buf, offset, len);
			ptr += x;
			return x;
		}

		@Override
		public int read(byte[] buf) throws IOException {
			return read(buf, 0, buf.length);
		}

		@Override
		public void close() throws IOException {
			f.close();
		}

	}


	/**
	 *
	 */
	public static class ReadOnlyFileSliceBucketException extends IOException {

		private static final long serialVersionUID = -1;

		/**
		 *
		 * @param e
		 */
		public ReadOnlyFileSliceBucketException(FileNotFoundException e) {
			super("File not found: " + e.getMessage());
			initCause(e);
		}

		/**
		 *
		 * @param string
		 */
		public ReadOnlyFileSliceBucketException(String string) {
			super(string);
		}

	}


	public void free() {
	}

	/**
	 *
	 * @return
	 */
	public SimpleFieldSet toFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putSingle("Type", "ReadOnlyFileSliceBucket");
		fs.putSingle("Filename", file.toString());
		fs.put("Offset", startAt);
		fs.put("Length", length);
		return fs;
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(file);
		container.delete(this);
	}

	/**
	 *
	 * @param container
	 */
	public void objectOnActivate(ObjectContainer container) {
		// Cascading activation of dependancies
		container.activate(file, 5);
	}

	public Bucket createShadow() throws IOException {
		String fnam = new String(file.getPath());
		File newFile = new File(fnam);
		return new ReadOnlyFileSliceBucket(newFile, startAt, length);
	}

}
