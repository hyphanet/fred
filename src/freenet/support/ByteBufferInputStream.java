/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * @author sdiz
 */
public class ByteBufferInputStream extends InputStream implements DataInput {
	protected ByteBuffer buf;

	public ByteBufferInputStream(byte[] array) {
		this(array, 0, array.length);
	}

	public ByteBufferInputStream(byte[] array, int offset, int length) {
		this(ByteBuffer.wrap(array, offset, length));
	}
	public ByteBufferInputStream(ByteBuffer buf) {
		this.buf = buf;
	}

	@Override
	public int read() throws IOException {
		try {
			return buf.get() & Integer.MAX_VALUE;
		} catch (BufferUnderflowException e) {
			return -1;
		}
	}
	
	
	public int remaining() {
		return buf.remaining();
	}

	public boolean readBoolean() throws IOException {
		try {
			return buf.get() != 0;
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public byte readByte() throws IOException {
		try {
			return buf.get();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public char readChar() throws IOException {
		try {
			return buf.getChar();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public double readDouble() throws IOException {
		try {
			return buf.getDouble();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public float readFloat() throws IOException {
		try {
			return buf.getFloat();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public void readFully(byte[] b) throws IOException {
		try {
			buf.get(b);
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public void readFully(byte[] b, int off, int len) throws IOException {
		try {
			buf.get(b, off, len);
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public int readInt() throws IOException {
		try {
			return buf.getInt();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public long readLong() throws IOException {
		try {
			return buf.getLong();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public short readShort() throws IOException {
		try {
			return buf.getShort();
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}


	public int readUnsignedByte() throws IOException {
		try {
			return buf.get() & 0xFF;
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public int readUnsignedShort() throws IOException {
		try {
			return buf.getShort() & 0xFFFF;
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

	public int skipBytes(int n) throws IOException {
		int skip = Math.min(n, buf.remaining());
		buf.position(buf.position() + skip);
		return skip;
	}

	public String readUTF() throws IOException {
		return DataInputStream.readUTF(this);
	}
	/**
	 * @deprecated {@link DataInputStream#readLine()} is deprecated, so why not?
	 */
	@Deprecated
	public String readLine() throws IOException {
		// hmmmm bad
		return new DataInputStream(this).readLine();
	}

	/**
	 * Slice a piece of ByteBuffer into a new ByteBufferInputStream
	 * 
	 * @param size
	 */
	public ByteBufferInputStream slice(int size) throws IOException {
		try {
			if (buf.remaining() < size)
				throw new EOFException();

			ByteBuffer bf2 = buf.slice();
			bf2.limit(size);
			
			skip(size);
			
			return new ByteBufferInputStream(bf2);
		} catch (BufferUnderflowException e) {
			throw (EOFException)new EOFException().initCause(e);
		}
	}

}
