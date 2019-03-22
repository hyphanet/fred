/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.filter;

import java.util.Arrays;

public class CodecPacket {
	protected byte[] payload = null;

	CodecPacket(byte[] payload) {
		this.payload = payload;
	}

	public byte[] toArray() {
		return payload;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(payload);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (!(obj instanceof CodecPacket)) return false;
		CodecPacket other = (CodecPacket) obj;
		if (!Arrays.equals(payload, other.payload)) return false;
		return true;
	}

	
}
