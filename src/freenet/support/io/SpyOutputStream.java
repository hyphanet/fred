package freenet.support.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import freenet.support.Logger;

/*
 * This code is part of fproxy, an HTTP proxy server for Freenet. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * @author ian
 * @see freenet.support.SpyInputStream
 */
public class SpyOutputStream extends FilterOutputStream {

	private String prefix = "";
	private TempFileBucket tfb = null;

	private void debugPrintLn(String text) {
		if (Logger.shouldLog(Logger.DEBUG,this))
			Logger.debug(this, text);
	}

	private final void checkValid() throws IOException {
		if (tfb.isReleased()) {
			throw new IOException(
				"Attempt to use a released TempFileBucket: " + prefix);
		}
	}

	/**
	 * Constructor for the SpyOutputStream object
	 * 
	 * @param tfb
	 * @param pref
	 * @exception IOException
	 */
	public SpyOutputStream(TempFileBucket tfb, String pref)
		throws IOException {
		super(null);
		OutputStream tmpOut = null;
		try {
			this.prefix = pref;
			this.tfb = tfb;
			checkValid();
			tmpOut = tfb.getRealOutputStream();
			out = tmpOut;
		} catch (IOException ioe) {
			//ioe.printStackTrace();
			debugPrintLn("SpyOutputStream ctr failed!: " + ioe.toString());
			try {
				if (tmpOut != null) {
					tmpOut.close();
				}
			} catch (Exception e0) {
				// NOP
			}
			debugPrintLn("SpyOutputStream ctr failed!: " + ioe.toString());
			throw ioe;
		}
		debugPrintLn("Created new OutputStream");
	}

	////////////////////////////////////////////////////////////
	// FilterOutputStream implementation

	public void write(int b) throws IOException {
		synchronized (tfb) {
			//       println(".write(b)");
			checkValid();
			out.write(b);
		}
	}

	public void write(byte[] buf) throws IOException {
		synchronized (tfb) {
			//       println(".write(buf)");
			checkValid();
			out.write(buf);
		}
	}

	public void write(byte[] buf, int off, int len) throws IOException {
		synchronized (tfb) {
			//       println(".write(buf,off,len)");
			checkValid();
			out.write(buf, off, len);
		}
	}

	public void flush() throws IOException {
		synchronized (tfb) {
			debugPrintLn(".flush()");
			checkValid();
			out.flush();
		}
	}

	public void close() throws IOException {
		synchronized (tfb) {
			debugPrintLn(".close()");
			checkValid();
			out.close();
			if (tfb.streams.contains(out)) {
				tfb.streams.removeElement(out);
			}
		}
	}
}
