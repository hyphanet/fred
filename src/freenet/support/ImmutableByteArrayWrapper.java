/**
 * 
 */
package freenet.support;

import java.util.Arrays;

public class ImmutableByteArrayWrapper {
	final byte[] data;
	final int hashCode;
	
	public ImmutableByteArrayWrapper(byte[] data) {
		this.data = data;
		hashCode = Fields.hashCode(data);
	}

	public boolean equals(Object o) {
		if(o instanceof ImmutableByteArrayWrapper) {
			ImmutableByteArrayWrapper w = (ImmutableByteArrayWrapper) o;
			if((w.hashCode == hashCode) &&
					Arrays.equals(data, w.data))
				return true;
		}
		return false;
	}
	
	public int hashCode() {
		return hashCode;
	}
}