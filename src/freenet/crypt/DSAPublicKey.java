/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.crypt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;

import freenet.node.FSParseException;
import freenet.store.StorableBlock;
import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

public class DSAPublicKey extends CryptoKey implements StorableBlock {

	private static final long serialVersionUID = -1;
	private final BigInteger y;
	public static final int PADDED_SIZE = 1024;
	public static final int HASH_LENGTH = 32;
	/** Null means use Global.DSAgroupBigA. This makes persistence simpler. */
	private final DSAGroup group;
	private volatile byte[] fingerprint;
	
	public DSAPublicKey(DSAGroup g, BigInteger y) {
		if(y.signum() != 1)
			throw new IllegalArgumentException();
		this.y = y;
		if(g == Global.DSAgroupBigA) g = null;
		this.group = g;
		if(y.compareTo(getGroup().getP()) > 0)
			throw new IllegalArgumentException("y must be < p but y=" + y + " p=" + g.getP());
	}

	/**
	 * Use this constructor if you have a Hex:ed version of y already
	 * available, will save some conversions and string allocations.
	 */
	public DSAPublicKey(DSAGroup g, String yAsHexString) throws NumberFormatException {
		this.y = new BigInteger(yAsHexString, 16);
		if(y.signum() != 1)
			throw new IllegalArgumentException();
		if(g == Global.DSAgroupBigA) g = null;
		this.group = g;
	}

	public DSAPublicKey(DSAGroup g, DSAPrivateKey p) {
		this(g, g.getG().modPow(p.getX(), g.getP()));
	}

	public DSAPublicKey(InputStream is) throws IOException, CryptFormatException {
		DSAGroup g = (DSAGroup) DSAGroup.read(is);
		if(g == Global.DSAgroupBigA) g = null;
		group = g;
		y = Util.readMPI(is);
		if(y.compareTo(getGroup().getP()) > 0)
			throw new IllegalArgumentException("y must be < p but y=" + y + " p=" + getGroup().getP());
	}

	public DSAPublicKey(byte[] pubkeyBytes) throws IOException, CryptFormatException {
		this(new ByteArrayInputStream(pubkeyBytes));
	}

	public static DSAPublicKey create(byte[] pubkeyAsBytes) throws CryptFormatException {
		try {
			return new DSAPublicKey(new ByteArrayInputStream(pubkeyAsBytes));
		} catch(IOException e) {
			throw new CryptFormatException(e);
		}
	}
	
	protected DSAPublicKey() {
	    // For serialization.
	    y = null;
	    group = null;
	}

	public BigInteger getY() {
		return y;
	}

	public BigInteger getP() {
		return getGroup().getP();
	}

	public BigInteger getQ() {
		return getGroup().getQ();
	}

	public BigInteger getG() {
		return getGroup().getG();
	}

	@Override
	public String keyType() {
		return "DSA.p";
	}

	// Nope, this is fine
	public final DSAGroup getGroup() {
		if(group == null) return Global.DSAgroupBigA;
		else return group;
	}

	public static CryptoKey read(InputStream i) throws IOException, CryptFormatException {
		return new DSAPublicKey(i);
	}

	public int keyId() {
		return y.intValue();
	}

	@Override
	public String toLongString() {
		return "y=" + HexUtil.biToHex(y);
	}

	@Override
	public byte[] asBytes() {
		byte[] groupBytes = getGroup().asBytes();
		byte[] ybytes = Util.MPIbytes(y);
		byte[] bytes = new byte[groupBytes.length + ybytes.length];
		System.arraycopy(groupBytes, 0, bytes, 0, groupBytes.length);
		System.arraycopy(ybytes, 0, bytes, groupBytes.length, ybytes.length);
		return bytes;
	}

	public byte[] asBytesHash() {
		byte[] hash = SHA256.digest(asBytes());
		return hash;
	}

	public byte[] asPaddedBytes() {
		byte[] asBytes = asBytes();
		if(asBytes.length == PADDED_SIZE)
			return asBytes;
		if(asBytes.length > PADDED_SIZE)
			throw new Error("Cannot fit key in " + PADDED_SIZE + " - real size is " + asBytes.length);
		return Arrays.copyOf(asBytes, PADDED_SIZE);
	}

	@Override
	public byte[] fingerprint() {
		byte[] fingerprint = this.fingerprint;
		if (fingerprint == null) {
			fingerprint = fingerprint(new BigInteger[]{y});
			this.fingerprint = fingerprint;
		}
		return fingerprint;
	}

	public boolean equals(DSAPublicKey o) {
		if(this == o) // Not necessary, but a very cheap optimization
			return true;
		return y.equals(o.y) && getGroup().equals(o.getGroup());
	}

	@Override
	public int hashCode() {
		return y.hashCode() ^ getGroup().hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) // Not necessary, but a very cheap optimization
			return true;
		else if((o == null) || (o.getClass() != this.getClass()))
			return false;
		return y.equals(((DSAPublicKey) o).y) && getGroup().equals(((DSAPublicKey) o).getGroup());
	}

	public int compareTo(Object other) {
		if(other instanceof DSAPublicKey)
			return getY().compareTo(((DSAPublicKey) other).getY());
		else
			return -1;
	}

	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("y", Base64.encode(y.toByteArray()));
		return fs;
	}

	public static DSAPublicKey create(SimpleFieldSet set, DSAGroup group) throws FSParseException {
		BigInteger x;
		try {
			x = new BigInteger(1, Base64.decode(set.get("y")));
		} catch (IllegalBase64Exception e) {
			throw new FSParseException(e);
		}
		try {
			return new DSAPublicKey(group, x);
		} catch (IllegalArgumentException e) {
			throw new FSParseException(e);
		}
	}

	@Override
	public byte[] getFullKey() {
		return asBytesHash();
	}

	@Override
	public byte[] getRoutingKey() {
		return asBytesHash();
	}

}
