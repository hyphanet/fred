/*
 * freenet - StreamCopier.java Copyright © 2007 David Roden
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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Helper class that copies bytes from an {@link InputStream} to an
 * {@link OutputStream}.
 * 
 * @author David &lsquo;Roden&rsquo; &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class StreamCopier {

	/** Default buffer size is 64k. */
	private static final int DEFAULT_BUFFER_SIZE = 1 << 16;

	/** The current buffer size. */
	private static int bufferSize = DEFAULT_BUFFER_SIZE;

	/**
	 * Sets the buffer size for following transfers.
	 * 
	 * @param bufferSize
	 *            The new buffer size
	 */
	public static void setBufferSize(int bufferSize) {
		StreamCopier.bufferSize = bufferSize;
	}

	/**
	 * Copies <code>length</code> bytes from the source input stream to the
	 * destination output stream. If <code>length</code> is <code>-1</code>
	 * as much bytes as possible will be copied (i.e. until
	 * {@link InputStream#read()} returns <code>-1</code> to signal the end of
	 * the stream).
	 * 
	 * @param source
	 *            The input stream to read from
	 * @param destination
	 *            The output stream to write to
	 * @param length
	 *            The number of bytes to copy
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static void copy(InputStream source, OutputStream destination, long length) throws IOException {
		long remaining = length;
		byte[] buffer = new byte[bufferSize];
		int read = 0;
		while ((remaining == -1) || (remaining > 0)) {
			read = source.read(buffer, 0, ((remaining > bufferSize) || (remaining == -1)) ? bufferSize : (int) remaining);
			if (read == -1) {
				if (length == -1) {
					return;
				}
				throw new EOFException("stream reached eof");
			}
			destination.write(buffer, 0, read);
			remaining -= read;
		}
	}

	/**
	 * Copies as much bytes as possible (i.e. until {@link InputStream#read()}
	 * returns <code>-1</code>) from the source input stream to the
	 * destination output stream.
	 * 
	 * @param source
	 *            The input stream to read from
	 * @param destination
	 *            The output stream to write to
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static void copy(InputStream source, OutputStream destination) throws IOException {
		copy(source, destination, -1);
	}

	/**
	 * Find the length of an input stream. This method will consume the complete
	 * input stream until its {@link InputStream#read(byte[])} method returns
	 * <code>-1</code>, thus signaling the end of the stream.
	 * 
	 * @param source
	 *            The input stream to find the length of
	 * @return The number of bytes that can be read from the stream
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static long findLength(InputStream source) throws IOException {
		long length = 0;
		byte[] buffer = new byte[bufferSize];
		int read = 0;
		while (read > -1) {
			read = source.read(buffer);
			if (read != -1) {
				length += read;
			}
		}
		return length;
	}

}
