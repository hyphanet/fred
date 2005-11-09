package freenet.support.io;

import java.io.IOException;
import java.io.InputStream;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * The purpose of all of this gobbledeygook is to keep rude FCPClient
 * implementations from writing to temp files after the requests that own them
 * have been canceled.
 * 
 * <p>
 * These could be removed once FCPClient implementation deficiencies have been
 * corrected but sanity checks are always useful.
 * </p>
 * 
 * @author ian
 * @see freenet.support.SpyOutputStream
 */
class SpyInputStream extends java.io.FilterInputStream {

    private String prefix = "";
	private TempFileBucket tfb = null;

	private final void println(String text) {
	}

	private final void checkValid() throws IOException {
		if (tfb.isReleased()) {
			throw new IOException(
				"Attempt to use a released TempFileBucket: " + prefix);
		}
	}

	/**
	 * Constructor for the SpyInputStream object
	 * 
	 * @param tfb
	 * @param prefix
	 * @exception IOException
	 */
	public SpyInputStream(TempFileBucket tfb, String prefix)
		throws IOException {
		super(null);
		InputStream tmpIn = null;
		try {
			this.prefix = prefix;
			this.tfb = tfb;
			checkValid();
			tmpIn = tfb.getRealInputStream();
			in = tmpIn;
		} catch (IOException ioe) {
			try {
				if (tmpIn != null) {
					tmpIn.close();
				}
			} catch (Exception e) {
				// NOP
			}
			throw ioe;
		}
		println("Created new InputStream");
	}

	public int read() throws java.io.IOException {
		synchronized (tfb) {
			println(".read()");
			checkValid();
			return in.read();
		}
	}

	public int read(byte[] bytes) throws java.io.IOException {
		synchronized (tfb) {
			println(".read(byte[])");
			checkValid();
			return in.read(bytes);
		}
	}

    public int read(byte[] bytes, int a, int b) throws java.io.IOException {
		synchronized (tfb) {
			println(".read(byte[], int, int)");
			checkValid();
			// FIXME remove debugging
			if(a+b > bytes.length || a < 0 || b < 0)
				throw new ArrayIndexOutOfBoundsException("a="+a+", b="+b+", length "+bytes.length);
			return in.read(bytes, a, b);
		}
	}
    
	public long skip(long a) throws java.io.IOException {
		synchronized (tfb) {
			println(".skip(long)");
			checkValid();
			return in.skip(a);
		}
	}

    public int available() throws java.io.IOException {
		synchronized (tfb) {
			println(".available()");
			checkValid();
			return in.available();
		}
	}
	
	public void close() throws java.io.IOException {
		synchronized (tfb) {
			println(".close()");
			checkValid();
			in.close();
			if (tfb.streams.contains(in)) {
				tfb.streams.removeElement(in);
			}
		}
	}

	
	public void mark(int a) {
		synchronized (tfb) {
			println(".mark(int)");
			in.mark(a);
		}
	}

	public void reset() throws java.io.IOException {
		synchronized (tfb) {
			println(".reset()");
			checkValid();
			in.reset();
		}
	}

    public boolean markSupported() {
		synchronized (tfb) {
			println(".markSupported()");
			return in.markSupported();
		}
	}
}
