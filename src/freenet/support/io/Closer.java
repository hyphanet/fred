/*
 * freenet - Closer.java Copyright © 2007 David Roden
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.support.io;

import java.io.Closeable;
import java.io.IOException;
import java.util.zip.ZipFile;

import freenet.support.Logger;
import freenet.support.api.Bucket;

/**
 * Closes various resources. The resources are checked for being
 * <code>null</code> before being closed, and every possible execption is
 * swallowed. That makes this class perfect for use in the finally blocks of
 * try-catch-finally blocks.
 * 
 * @author David &lsquo;Roden&rsquo; &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 * @deprecated Java 7 has a new language feature which mostly does what this class was for:
 *             <a href="http://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html">The try with-resources Statement</a>.<br/>
 *             There are some differences with regards to swallowing Exceptions, please study them carefully when replacing Closer usage with it.
 */
@Deprecated
public class Closer {
	/**
	 * Closes the given stream.
	 * 
	 * @param closable The output stream to close
	 */
	public static void close(Closeable closable) {
		if (closable != null) {
			try {
				closable.close();
			} catch (IOException e) {
				Logger.error(Closer.class, "Error during close() on "+closable, e);
			}
		}
	}
	
	/**
	 * Frees the given bucket. Notice that you have to do removeFrom() for persistent buckets yourself.
	 * @param bucket The Bucket to close.
	 */
	public static void close(Bucket bucket) {
		if (bucket != null) {
			try { 
				bucket.free();
			} catch(RuntimeException e) {
				Logger.error(Closer.class, "Error during free().", e);
			}
		}
	}

	/**
	 * Closes the given zip file.
	 * 
	 * @param zipFile
	 *            The zip file to close
	 */
	public static void close(ZipFile zipFile) {
		if (zipFile != null) {
			try {
				zipFile.close();
			} catch (IOException e) {
				Logger.error(Closer.class, "Error during close().", e);
			}
		}
	}

}
