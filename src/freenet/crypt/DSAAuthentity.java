package freenet.crypt;

import java.math.BigInteger;
import java.util.Random;

import net.i2p.util.NativeBigInteger;

import freenet.crypt.CryptoElement;
import freenet.crypt.CryptoKey;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.support.HexUtil;

public class DSAAuthentity extends DSAPrivateKey implements Authentity {

	private DSAGroup grp;

	public DSAAuthentity(BigInteger x, DSAGroup grp) {
		super(x);
		this.grp = grp;
	}

	public DSAAuthentity(DSAGroup g, Random r) {
		super(g, r);
		this.grp = g;
	}

	/**
	 * Construct the authentity from hex string values.
	 * 
	 * @param fs
	 *            a FieldSet containing x, p, q, and g
	 */
	public DSAAuthentity(FieldSet fs) throws NumberFormatException {
		this(getX(fs), getGroup(fs));
	}

	private static final DSAGroup getGroup(FieldSet fs)
		throws NumberFormatException {
		BigInteger p, q, g;
		try {
			p = new NativeBigInteger(fs.getString("p"), 16);
			q = new NativeBigInteger(fs.getString("q"), 16);
			g = new NativeBigInteger(fs.getString("g"), 16);
		} catch (NullPointerException e) {
			// yea, i know, don't catch NPEs .. but _some_ JVMs don't
			// throw the NFE like they are supposed to (*cough* kaffe)
			throw new NumberFormatException("" + e);
		}
		return new DSAGroup(p, q, g);
	}

	private static final BigInteger getX(FieldSet fs)
		throws NumberFormatException {
		try {
			return new NativeBigInteger(fs.getString("x"), 16);
		} catch (NullPointerException e) {
			// yea, i know, don't catch NPEs .. but _some_ JVMs don't
			// throw the NFE like they are supposed to (*cough* kaffe)
			throw new NumberFormatException("" + e);
		}
	}

	/**
	 * Returns the signature of the byte digest.
	 */
	public CryptoElement sign(byte[] digest) {
		return sign(new NativeBigInteger(1, digest));
	}

	public CryptoElement sign(BigInteger b) {
		return DSA.sign(grp, this, b, Core.getRandSource());
	}

	/**
	 * @return a FieldSet containing the private key and DSA group primitives
	 *         as hex strings (x, p, q, g)
	 */
	public final FieldSet getFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("x", HexUtil.biToHex(getX()));
		fs.put("p", HexUtil.biToHex(grp.getP()));
		fs.put("q", HexUtil.biToHex(grp.getQ()));
		fs.put("g", HexUtil.biToHex(grp.getG()));
		return fs;
	}

	/**
	 * Returns the Cryptographic private key of this authentity
	 */
	public final CryptoKey getKey() {
		return this;
	}

	/**
	 * @return a DSAIdentity made from this
	 */
	public final Identity getIdentity() {
		return new DSAIdentity(grp, this);
	}

}
