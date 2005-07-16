package freenet;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import freenet.crypt.CryptoElement;
import freenet.crypt.CryptoKey;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Util;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * A unique identifier for a NodeReference, or a client. Basically, just wraps
 * DSAPublicKey, but provides hashCode() for use in a Hashtable, plus ...
 * 
 * @author tavin
 */
public class DSAIdentity extends DSAPublicKey implements Identity {
    
	private final int hashed;

	/**
	 * Create a DSAIdentity
	 * 
	 * @param g
	 *            The DSA group to use.
	 * @param x
	 *            The DSAAuthentity private key to use.
	 */
	public DSAIdentity(DSAGroup g, DSAAuthentity x) {
		super(g, x);
		hashed = makeHashCode();
	}

	/**
	 * Create a DSAIdentity
	 * 
	 * @param g
	 *            The DSA group to use.
	 * @param y
	 *            Publci key value.
	 */
	public DSAIdentity(DSAGroup g, BigInteger y) {
		super(g, y);
		hashed = makeHashCode();
	}

	/**
	 * Create a DSAIdentity
	 * 
	 * @param g
	 *            The DSA group to use.
	 * @param yAsHexString
	 *            Public key value as hex string.
	 */
	public DSAIdentity(DSAGroup g, String yAsHexString) {
		super(g, yAsHexString);
		hashed = makeHashCode();
	}

	/**
	 * Construct the identity from hex string values.
	 * 
	 * @param fs
	 *            a FieldSet containing y, p, q, and g
	 */
	public DSAIdentity(FieldSet fs) throws NumberFormatException {
		this(getGroup(fs), getYasHexString(fs));
	}

	private static final DSAGroup getGroup(FieldSet fs)
		throws NumberFormatException {
		return new DSAGroup(fs.getString("p"), fs.getString("q"), fs.getString("g"));
	}

	private static final String getYasHexString(FieldSet fs){
		return fs.getString("y");
	}

	public static CryptoKey read(InputStream i) throws IOException {
		BigInteger y = Util.readMPI(i);
		// in DSAPublicKey Soctt used the generic read in CryptoKey,
		// but that requires the classname as a UTF first, he can't be
		// planning to write the classname to the protocol???
		//		DSAGroup g=(DSAGroup)DSAGroup.read(i);
		// it seems like he is, remind me to complain like hell...
		//      DSAGroup g=(DSAGroup)CryptoKey.read(i);
		// but then he made that method package only
		DSAGroup g = (DSAGroup) DSAGroup.read(i);
		return new DSAIdentity(g, y);
	}

	public boolean verify(String sig, BigInteger digest) {
		try {
			return DSA.verify(this, new DSASignature(sig), digest);

		} catch (NumberFormatException e) {
			Core.logger.log(
				this,
				"Signature check failed as signature was "
					+ "not DSA: "
					+ e.getMessage(),
				Logger.MINOR);
			return false;
		}
	}

	public boolean verify(CryptoElement sig, BigInteger digest) {
		if (sig instanceof DSASignature) {
			return DSA.verify(this, (DSASignature) sig, digest);
		} else {
			// above could be another nodes fault, but if we created a
			// signature element of the wrong type we are fucking up.
			Core.logger.log(
				this,
				"Signature check failed as signature was not DSA.",
				Logger.ERROR);
			return false;
		}
	}

	/**
	 * @return a FieldSet containing the public key and DSA group primitives as
	 *         hex strings (y, p, q, g)
	 */
	public final FieldSet getFieldSet() {
		FieldSet fs = new FieldSet();
		fs.put("y", getYAsHexString());
		fs.put("p", getPAsHexString());
		fs.put("q", getQAsHexString());
		fs.put("g", getGAsHexString());
		return fs;
	}

	public final int hashCode() {
		return hashed;
	}

	private final int makeHashCode() {
		return getY().shiftRight(getY().bitLength() - 32).intValue();
	}

	public final int compareTo(Object o) {
		if (o == null)
			return 1;
		else if (o instanceof Identity)
			return Fields.compareBytes(
				fingerprint(),
				((Identity) o).fingerprint());
		else
			throw new IllegalArgumentException();
	}

	public final CryptoKey getKey() {
		return this;
	}

	public final String toString() {
		return "DSA(" + fingerprintToString() + ')';
	}
}
