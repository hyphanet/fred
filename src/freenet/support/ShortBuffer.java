/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.support;

import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import freenet.io.WritableToDataOutputStream;

/**
 * Byte array which is limited to 32KiB.
 */
public class ShortBuffer implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: ShortBuffer.java,v 1.2 2005/08/25 17:28:19 amphibian Exp $";

	private final byte[] _data;
	private final int _start;
	private final short _length;

	/**
	 * Create a Buffer by reading a DataInputStream
	 *
	 * @param dis to read bytes from
	 * @throws IllegalArgumentException If the length integer is negative.
	 * @throws IOException error reading from dis
	 */
	public ShortBuffer(DataInput dis) throws IOException {
		_length = dis.readShort();
		if(_length < 0)
			throw new IllegalArgumentException("Negative Length: "+_length);
		_data = new byte[_length];
		_start = 0;
		dis.readFully(_data);
	}

	/**
	 * Create an empty Buffer */
	public ShortBuffer() {
		_data = new byte[0];
		_start = 0;
		_length = 0;
	}
	
	/**
	 * Create a Buffer from a byte array
	 *
	 * @param data
	 */
	public ShortBuffer(byte[] data) {
		if(data.length > Short.MAX_VALUE)
		    throw new IllegalArgumentException("Too big: "+data.length);
		_start = 0;
		_length = (short)data.length;
		_data = data;
	}

	public ShortBuffer(byte[] data, int start, int length) {
		if(length > Short.MAX_VALUE || length < 0 || start < 0 || start + length > data.length)
		    throw new IllegalArgumentException("Invalid Length: start=" + start + ", length=" + length);
		_start = start;
		_data = data;
		_length = (short)length;
	}

	/**
	 * Retrieve a byte array containing the data in this buffer.
	 * May be copied, so don't rely on it being the internal
	 * buffer.
	 *
	 * @return The byte array
	 */
	public byte[] getData() {
		if ((_start == 0) && (_length == _data.length)) {
			return _data;
		} else {
			return Arrays.copyOfRange(_data, _start, _start+_length);
		}
	}

	/**
	 * Copy the data to a byte array
	 *
	 * @param array
	 * @param position
	 */
	public void copyTo(byte[] array, int position) {
		System.arraycopy(_data, _start, array, position, _length);
	}

	public byte byteAt(int pos) {
		if (pos >= _length) {
			throw new ArrayIndexOutOfBoundsException();
		}
		return _data[pos + _start];
	}

	@Override
	public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
		stream.writeShort(_length);
		stream.write(_data, _start, _length);
	}

	@Override
	public String toString() {
		if (this._length > 50) {
			return "Buffer {"+this._length+ '}';
		} else {
			StringBuilder b = new StringBuilder(this._length*3);
            b.append('{').append(this._length).append(':');
			for (int x=0; x<this._length; x++) {
				b.append(byteAt(x));
				b.append(' ');
			}
			return b.toString();
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ShortBuffer)) {
			return false;
		}

		final ShortBuffer buffer = (ShortBuffer) o;

		if (_length != buffer._length) {
			return false;
		}
		if (_start != buffer._start) {
			return false;
		}
		if (!Arrays.equals(_data, buffer._data)) {
			return false;
		}

		return true;
	}

	@Override
	public int hashCode() {
	    return Fields.hashCode(_data) ^ _start ^ (_length << 16);
	}
	
	public int getLength() {
		return _length;
	}
}
