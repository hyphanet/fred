package freenet.crypt;

import freenet.node.NodeCrypto;
import net.i2p.util.NativeBigInteger;

public class DiffieHellmanLightContext {

	/** My exponent.*/
	public final NativeBigInteger myExponent;
	/** My exponential. This is group.g ^ myExponent mod group.p */
	public final NativeBigInteger myExponential;
	/** The group we both share */
	public final DHGroup group;
	/** The signature of (g^r, grpR) */
	public final DSASignature signature;

	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(super.toString());
		sb.append(": myExponent=");
		sb.append(myExponent.toHexString());
		sb.append(", myExponential=");
		sb.append(myExponential.toHexString());
		
		return sb.toString();
	}

	// FIXME: remove the layering violation, sign it *before* the constructor so that it doesn't need NodeCrypto
	public DiffieHellmanLightContext(NodeCrypto crypto, NativeBigInteger myExponent, NativeBigInteger myExponential, DHGroup group) {
		this.myExponent = myExponent;
		this.myExponential = myExponential;
		this.group = group;
		
		byte[] _myExponential = myExponential.toByteArray();
		byte[] _myGroup = group.asBytes();
		byte[] toSign = new byte[_myExponential.length + _myGroup.length];
		System.arraycopy(_myExponential, 0, toSign, 0, _myExponential.length);
		System.arraycopy(_myGroup, 0, toSign, _myExponential.length, _myGroup.length);
		this.signature = crypto.sign(SHA256.digest(toSign));
	}
}