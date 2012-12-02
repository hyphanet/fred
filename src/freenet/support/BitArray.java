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

public class BitArray implements WritableToDataOutputStream {

    public static final String VERSION = "$Id: BitArray.java,v 1.2 2005/08/25 17:28:19 amphibian Exp $";

	private int _size;
	private byte[] _bits;

	public BitArray(byte[] data) {
		_bits = data;
		_size = data.length*8;
	}
	
	public BitArray copy() {
		return new BitArray(this);
	}
	
	/**
	 * This constructor does not check for unacceptable sizes, and should only be used on trusted data.
	 */
	public BitArray(DataInput dis) throws IOException {
		_size = dis.readInt();
		_bits = new byte[(_size + 7) / 8];
		dis.readFully(_bits);
	}
	
	public BitArray(DataInput dis, int maxSize) throws IOException {
		_size = dis.readInt();
		if (_size<=0 || _size>maxSize)
			throw new IOException("Unacceptable bitarray size: "+_size);
		_bits = new byte[(_size + 7) / 8];
		dis.readFully(_bits);
	}

	public BitArray(int size) {
		_size = size;
		_bits = new byte[(size + 7) / 8];
	}

	public BitArray(BitArray src) {
		this._size = src._size;
		this._bits = src._bits.clone();
	}
	
	public void setBit(int pos, boolean f) {
		if(pos > _size) throw new ArrayIndexOutOfBoundsException();
		int b = unsignedByteToInt(_bits[pos / 8]);
		int mask = (1 << (pos % 8));
		if (f) {
			_bits[pos / 8] = (byte) (b | mask);
		} else {
			_bits[pos / 8] = (byte) (b & (~mask));
		}
	}

	public boolean bitAt(int pos) {
		if(pos > _size) throw new ArrayIndexOutOfBoundsException();
		int b = unsignedByteToInt(_bits[pos / 8]);
		int mask = (1 << (pos % 8));
		return (b & mask) != 0;
	}

	public static int unsignedByteToInt(byte b) {
		return b & 0xFF;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(this._size);
		for (int x=0; x<_size; x++) {
			if (bitAt(x)) {
				sb.append('1');
			} else {
				sb.append('0');
			}
		}
		return sb.toString();
	}

	@Override
	public void writeToDataOutputStream(DataOutputStream dos) throws IOException {
		dos.writeInt(_size);
		dos.write(_bits);
	}

	public static int serializedLength(int size) {
		return ((size + 7) / 8) + 4;
	}

	public int getSize() {
		return _size;
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof BitArray)) {
			return false;
		}
		BitArray ba = (BitArray) o;
		if (ba.getSize() != getSize()) {
			return false;
		}
		for (int x=0; x<getSize(); x++) {
			if (ba.bitAt(x) != bitAt(x)) {
				return false;
			}
		}
		return true;
	}
	
	@Override
	public int hashCode() {
	    return Fields.hashCode(_bits);
	}

	public void setAllOnes() {
		for(int i=0;i<_bits.length;i++)
			_bits[i] = (byte)0xFF;
	}

	public int firstOne(int start) {
		int startByte = start/8;
		int startBit = start%8;
		for(int i=startByte;i<_bits.length;i++) {
			byte b = _bits[i];
			if(b == 0) continue;
			for(int j=startBit;j<8;j++) {
				int mask = (1 << j);
				if((b & mask) != 0) {
					int x = i*8+j;
					if(x >= _size) return -1;
					return x;
				}
			}
			startBit = 0;
		}
		return -1;
	}
	
	public int firstOne() {
		return firstOne(0);
	}

	public int firstZero(int start) {
		int startByte = start/8;
		int startBit = start%8;
		for(int i=startByte;i<_bits.length;i++) {
			byte b = _bits[i];
			if(b == (byte)255) continue;
			for(int j=startBit;j<8;j++) {
				int mask = (1 << j);
				if((b & mask) == 0) {
					int x = i*8+j;
					if(x >= _size) return -1;
					return x;
				}
			}
			startBit = 0;
		}
		return -1;
	}

	public void setSize(int size) {
		if(_size == size) return;
		int oldSize = _size;
		_size = size;
		int bytes = (size + 7) / 8;
		if(_bits.length != bytes) {
			_bits = Arrays.copyOf(_bits, bytes);
		}
		if(oldSize < _size && oldSize % 8 != 0) {
			for(int i=oldSize;i<Math.min(_size, oldSize - oldSize % 8 + 8);i++) {
				setBit(i, false);
			}
		}
	}

	public int lastOne(int start) {
		if(start >= _size) start = _size-1;
		int startByte = start/8;
		int startBit = start%8;
		if(startByte >= _bits.length) {
			System.err.println("Start byte is "+startByte+" _bits.length is "+_bits.length);
			assert(false);
		}
		for(int i=startByte;i>=0;i--,startBit=7) {
			byte b = _bits[i];
			if(b == (byte)0) continue;
			for(int j=startBit;j>=0;j--) {
				int mask = (1 << j);
				if((b & mask) != 0) {
					int x = i*8+j;
					return x;
				}
			}
		}
		return -1;
	}
}
