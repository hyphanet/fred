/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;

import freenet.support.HexUtil;
import freenet.support.Logger;

public abstract class CryptoKey implements CryptoElement, Serializable {

    private static final long serialVersionUID = 1L;

	CryptoKey() {
	}

	public static CryptoKey read(InputStream i) throws IOException, CryptFormatException {
		DataInputStream dis = new DataInputStream(i);
		String type = dis.readUTF();
		try {
			Class<?> keyClass = Class.forName(type);
			Method m =
				keyClass.getMethod("read", new Class<?>[] { InputStream.class });
			return (CryptoKey) m.invoke(null, dis);
		} catch (Exception e) {
			e.printStackTrace();
			if (e instanceof CryptFormatException)
				throw (CryptFormatException) e;
			if (e instanceof IOException)
				throw (IOException) e;
			Logger.error(CryptoKey.class, "Unknown exception while reading CryptoKey", e);
			return null;
		}
	}

	public abstract String keyType();
	public abstract byte[] fingerprint();
	public abstract byte[] asBytes();

	protected byte[] fingerprint(BigInteger[] quantities) {
		MessageDigest shactx = HashType.SHA1.get();
		for (BigInteger quantity: quantities) {
			byte[] mpi = Util.MPIbytes(quantity);
			shactx.update(mpi, 0, mpi.length);
		}
		return shactx.digest();
	}

	public String verboseToString() {
		StringBuilder b = new StringBuilder();
		b.append(toString()).append('\t').append(fingerprintToString());
		return b.toString();
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(keyType().length() + 1 + 4);
		b.append(keyType()).append('/');
		HexUtil.bytesToHexAppend(fingerprint(), 16, 4, b);
		return b.toString();
	}

	public String fingerprintToString() {
		String fphex = HexUtil.bytesToHex(fingerprint());
		StringBuilder b = new StringBuilder(40 + 10);
		b
			.append(fphex.substring(0, 4))
			.append(' ')
			.append(fphex.substring(4, 8))
			.append(' ')
			.append(fphex.substring(8, 12))
			.append(' ')
			.append(fphex.substring(12, 16))
			.append(' ')
			.append(fphex.substring(16, 20))
			.append("  ")
			.append(fphex.substring(20, 24))
			.append(' ')
			.append(fphex.substring(24, 28))
			.append(' ')
			.append(fphex.substring(28, 32))
			.append(' ')
			.append(fphex.substring(32, 36))
			.append(' ')
			.append(fphex.substring(36, 40));
		return b.toString();
	}

}
