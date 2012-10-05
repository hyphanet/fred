/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Arrays;

/**
 * byte[], but can be put into HashSet etc *by content*.
 * @author toad
 */
public class ByteArrayWrapper implements Comparable<ByteArrayWrapper> {
	
	private final byte[] buf;
	private int hashCode;
	
	public ByteArrayWrapper(byte[] data) {
		buf = data;
		hashCode = Fields.hashCode(buf);
	}
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof ByteArrayWrapper) {
			ByteArrayWrapper b = (ByteArrayWrapper) o;
			if(b.buf == buf) return true;
			return Arrays.equals(b.buf, buf);
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	/** DO NOT MODIFY THE RETURNED DATA! */
	public byte[] get() {
		return buf;
	}

	@Override
	public int compareTo(ByteArrayWrapper arg) {
		return Fields.compareBytes(buf, arg.buf);
	}
}
