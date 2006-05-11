package freenet.crypt;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.StringTokenizer;

import net.i2p.util.NativeBigInteger;
import freenet.support.HexUtil;

/**
 * Holds a Diffie-Hellman key-exchange group
 */
public class DHGroup extends CryptoKey {
	private static final long serialVersionUID = -1;
	public final BigInteger p, g;

	public DHGroup(BigInteger p, BigInteger g) {
		this.p = p;
		this.g = g;
	}

//	public void write(OutputStream out) throws IOException {
//		super.write(out, getClass().getName());
//	}
//
	public String writeAsField() {
		String pStr = HexUtil.biToHex(p);
		String gStr = HexUtil.biToHex(g);
		StringBuffer b = new StringBuffer(pStr.length() + gStr.length() + 1);
		b.append(pStr).append(',').append(gStr);
		return b.toString();
	}

	public static CryptoKey readFromField(String field) {
		BigInteger p, q, g;
		StringTokenizer str = new StringTokenizer(field, ",");
		p = new NativeBigInteger(1, HexUtil.hexToBytes(str.nextToken()));
		g = new NativeBigInteger(1, HexUtil.hexToBytes(str.nextToken()));
		return new DHGroup(p, g);
	}

	public static CryptoKey read(DataInputStream i) throws IOException {
		BigInteger p, g;
		p = Util.readMPI(i);
		g = Util.readMPI(i);
		return new DHGroup(p, g);
	}

	public BigInteger getP() {
		return p;
	}

	public BigInteger getG() {
		return g;
	}

	public String keyType() {
		return "DHG-" + p.bitLength();
	}

	public byte[] fingerprint() {
		return fingerprint(new BigInteger[] { p, g });
	}

	public byte[] asBytes() {
		byte[] pb = Util.MPIbytes(p);
		byte[] gb = Util.MPIbytes(g);
		byte[] tb = new byte[pb.length + gb.length];
		System.arraycopy(pb, 0, tb, 0, pb.length);
		System.arraycopy(gb, 0, tb, pb.length, gb.length);
		return tb;
	}
}
