package freenet.crypt;

import freenet.support.Logger;

import net.i2p.util.NativeBigInteger;

public class DiffieHellmanLightContext {

	/** My exponent.*/
	public final NativeBigInteger myExponent;
	/** My exponential. This is group.g ^ myExponent mod group.p */
	public final NativeBigInteger myExponential;
	/** The group we both share */
	public final DHGroup group;
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

	public DiffieHellmanLightContext(NativeBigInteger myExponent, NativeBigInteger myExponential, DHGroup group) {
		this.myExponent = myExponent;
		this.myExponential = myExponential;
		this.group = group;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}
	
	public void setSignature(DSASignature sig) {
		this.signature = sig;
	}
	
	/*
	 * Calling the following is costy; avoid
	 */
	public NativeBigInteger getHMACKey(NativeBigInteger peerExponential, NativeBigInteger groupP) {		
		if(logMINOR)
			Logger.minor(this, "My exponent: "+myExponent.toHexString()+", my exponential: "+myExponential.toHexString()+", peer's exponential: "+peerExponential.toHexString());
		NativeBigInteger sharedSecret =
			(NativeBigInteger) peerExponential.modPow(myExponent, groupP);
		if(logMINOR)
			Logger.minor(this, "g^ir mod p = " + sharedSecret.toString());
		
		return sharedSecret;
	}
}