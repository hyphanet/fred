package freenet.crypt;

import freenet.support.Logger;

import net.i2p.util.NativeBigInteger;

public class DiffieHellmanLightContext {

	/** My exponent.*/
	public final NativeBigInteger myExponent;
	/** My exponential. This is group.g ^ myExponent mod group.p */
	public final NativeBigInteger myExponential;
	/** The signature of (g^r, grpR) */
	public DSASignature signature = null;
	
	private final boolean logMINOR;

	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append(super.toString());
		sb.append(": myExponent=");
		sb.append(myExponent.toHexString());
		sb.append(", myExponential=");
		sb.append(myExponential.toHexString());
		
		return sb.toString();
	}

	public DiffieHellmanLightContext(NativeBigInteger myExponent, NativeBigInteger myExponential) {
		this.myExponent = myExponent;
		this.myExponential = myExponential;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void setSignature(DSASignature sig) {
		this.signature = sig;
	}
	
	/*
	 * Calling the following is costy; avoid
	 */
	public NativeBigInteger getHMACKey(NativeBigInteger peerExponential, DSAGroup group) {		
		if(logMINOR)
			Logger.minor(this, "My exponent: "+myExponent.toHexString()+", my exponential: "+myExponential.toHexString()+", peer's exponential: "+peerExponential.toHexString());
		NativeBigInteger sharedSecret =
			(NativeBigInteger) peerExponential.modPow(myExponent, group.getP());
		if(logMINOR)
			Logger.minor(this, "g^ir mod p = " + sharedSecret.toString());
		
		return sharedSecret;
	}
}