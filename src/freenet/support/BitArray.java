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
import java.util.BitSet;

import freenet.io.WritableToDataOutputStream;

public class BitArray implements WritableToDataOutputStream {

	private int size;
	private final BitSet bits;

	public BitArray(byte[] data) {
		this.bits = BitSet.valueOf(data);
		this.size = data.length * 8;
	}
	
	public BitArray copy() {
		return new BitArray(this);
	}
	
	/**
	 * This constructor does not check for unacceptable sizes, and should only be used on trusted data.
	 */
	public BitArray(DataInput dis) throws IOException {
		this(dis, Integer.MAX_VALUE);
	}
	
	public BitArray(DataInput dis, int maxSize) throws IOException {
		this.size = dis.readInt();
		if (size <= 0 || size > maxSize) {
			throw new IOException("Unacceptable bitarray size: " + size);
		}
		byte[] inputBits = new byte[getByteSize()];
		dis.readFully(inputBits);
		this.bits = BitSet.valueOf(inputBits);
		trimToSize();
	}

	public BitArray(int size) {
		this.size = size;
		this.bits = new BitSet(size);
	}

	public BitArray(BitArray src) {
		this.size = src.size;
		this.bits = (BitSet)src.bits.clone();
	}

	public void setBit(int pos, boolean f) {
		checkPos(pos);
		bits.set(pos, f);
	}

	public boolean bitAt(int pos) {
		checkPos(pos);
		return bits.get(pos);
	}

	static int unsignedByteToInt(byte b) {
		return b & 0xFF;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(this.size);
		for (int x = 0; x < size; x++) {
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
		dos.writeInt(size);
		byte[] outputBits = bits.toByteArray();
		if (outputBits.length != getByteSize()) {
			outputBits = Arrays.copyOf(outputBits, getByteSize());
		}
		dos.write(outputBits);
	}

	public static int serializedLength(int size) {
		return toByteSize(size) + 4;
	}

	public int getSize() {
		return size;
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
		return bits.equals(ba.bits);
	}
	
	@Override
	public int hashCode() {
	    return bits.hashCode() ^ size;
	}

	public void setAllOnes() {
		bits.set(0, size);
	}

	public int firstOne(int start) {
		return bits.nextSetBit(start);
	}
	
	public int firstOne() {
		return firstOne(0);
	}

	public int firstZero(int start) {
		int result = bits.nextClearBit(start);
		if (result >= size) {
			return -1;
		}
		return result;
	}

	public void setSize(int size) {
		this.size = size;
		trimToSize();
	}

	public int lastOne(int start) {
		return bits.previousSetBit(start);
	}

	private void trimToSize() {
		bits.clear(size, Integer.MAX_VALUE);
	}

	private int getByteSize() {
		return toByteSize(size);
	}

	private static int toByteSize(int bitSize) {
		return (bitSize + 7) / 8;
	}

	private void checkPos(int pos) {
		if (pos > size || pos < 0) {
			throw new ArrayIndexOutOfBoundsException();
		}
	}
}
