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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * Closes various resources. The resources are checked for being
 * <code>null</code> before being closed, and every possible execption is
 * swallowed. That makes this class perfect for use in the finally blocks of
 * try-catch-finally blocks.
 * 
 * @author David &lsquo;Roden&rsquo; &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class Closer {

	/**
	 * Closes the given output stream.
	 * 
	 * @param outputStream
	 *            The output stream to close
	 */
	public static void close(OutputStream outputStream) {
		if (outputStream != null) {
			try {
				outputStream.close();
			} catch (IOException ioe1) {
			}
		}
	}

	/**
	 * Closes the given input stream.
	 * 
	 * @param inputStream
	 *            The input stream to close
	 */
	public static void close(InputStream inputStream) {
		if (inputStream != null) {
			try {
				inputStream.close();
			} catch (IOException ioe1) {
			}
		}
	}

	/**
	 * Closes the given jar file.
	 * 
	 * @param jarFile
	 *            The jar file to close
	 */
	public static void close(JarFile jarFile) {
		if (jarFile != null) {
			try {
				jarFile.close();
			} catch (IOException e) {
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
			}
		}
	}

}
