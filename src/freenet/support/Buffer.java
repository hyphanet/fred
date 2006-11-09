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

import java.io.*;
import java.util.*;

import freenet.io.WritableToDataOutputStream;

/**
 * Wrapper for a byte array which handles serialisation/deserialisation
 *
 * @author ian
 */
public class Buffer implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: Buffer.java,v 1.2 2005/08/25 17:28:19 amphibian Exp $";

	private final byte[] _data;
	private final int _start;
	private final int _length;

	/**
	 * Create a Buffer by reading a DataInputStream
	 *
	 * @param dis
	 * @throws IOException
	 */
	public Buffer(DataInputStream dis) throws IOException {
		_data = new byte[dis.readInt()];
		_length = _data.length;
		_start = 0;
		dis.readFully(_data);
	}

	/**
	 * Create a Buffer from a byte array
	 *
	 * @param data
	 */
	public Buffer(byte[] data) {
		_start = 0;
		_length = data.length;
		_data = data;
	}

	public Buffer(byte[] data, int start, int length) {
		_start = start;
		_data = data;
		_length = length;
	}

	/**
	 * Retrieve a byte array containing the data in this buffer.
	 * Will be copied if the data does not fill the whole array,
	 * so please don't rely on it being the internal buffer.
	 *
	 * @return The byte array
	 */
	public byte[] getData() {
		if ((_start == 0) && (_length == _data.length)) {
			return _data;
		} else {
			byte[] r = new byte[_length];
			System.arraycopy(_data, _start, r, 0, _length);
			return r;
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

	public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
		stream.writeInt(_length);
		stream.write(_data, _start, _length);
	}

	public String toString() {
		if (this._length > 50) {
			return "Buffer {"+this._length+"}";
		} else {
			StringBuffer b = new StringBuffer(this._length*3);
            b.append("{").append(this._length).append(":");
			for (int x=0; x<this._length; x++) {
				b.append(byteAt(x));
				b.append(' ');
			}
			return b.toString();
		}
	}

	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Buffer)) {
			return false;
		}

		final Buffer buffer = (Buffer) o;

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

	public int hashCode() {
	    return Fields.hashCode(_data) ^ _start ^ _length;
	}
	
	public int getLength() {
		return _length;
	}
}