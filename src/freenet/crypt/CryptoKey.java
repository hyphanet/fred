/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;

import freenet.support.HexUtil;

public abstract class CryptoKey implements CryptoElement, Serializable {

    private static final long serialVersionUID = 1L;

	CryptoKey() {
	}

	public abstract String keyType();
	public abstract byte[] fingerprint();
	public abstract byte[] asBytes();

	static byte[] fingerprint(BigInteger... quantities) {
		MessageDigest shactx = HashType.SHA1.get();
		for (BigInteger quantity : quantities) {
			byte[] mpi = Util.MPIbytes(quantity);
			shactx.update(mpi, 0, mpi.length);
		}
		return shactx.digest();
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(keyType().length() + 1 + 4);
		b.append(keyType()).append('/');
		HexUtil.bytesToHexAppend(fingerprint(), 16, 4, b);
		return b.toString();
	}

}
