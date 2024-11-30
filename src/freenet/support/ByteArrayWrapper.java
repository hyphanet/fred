/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.Arrays;
import java.util.Comparator;

/**
 * byte[], but can be put into HashSet etc *by content*.
 * @author toad
 */
public class ByteArrayWrapper implements Comparable<ByteArrayWrapper> {
	
	private final byte[] data;
	private final int hashCode;
	
	public static final Comparator<ByteArrayWrapper> FAST_COMPARATOR = Comparator
			.comparingInt(ByteArrayWrapper::hashCode)
			.thenComparing(Comparator.naturalOrder());
	
	public ByteArrayWrapper(byte[] data) {
		this.data = Arrays.copyOf(data, data.length);
		this.hashCode = Arrays.hashCode(this.data);
	}
	
	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (other instanceof ByteArrayWrapper) {
			ByteArrayWrapper b = (ByteArrayWrapper) other;
			return this.hashCode == b.hashCode && Arrays.equals(this.data, b.data);
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	public byte[] get() {
		return Arrays.copyOf(data, data.length);
	}

	@Override
	public int compareTo(ByteArrayWrapper other) {
		if (this == other) {
			return 0;
		}
		return Fields.compareBytes(this.data, other.data);
	}
}
