package freenet.crypt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.HexUtil;
import freenet.support.Logger;

public class DiffieHellmanContext {

    // Set on startup
    /** My exponent. We keep this and then raise our peer's exponential to this power. */
    final NativeBigInteger myExponent;
    /** My exponential. This is group.g ^ myExponent mod group.p */
    final NativeBigInteger myExponential;
    /** The group we both share */
    final DHGroup group;
    
    // Generated or set later
    NativeBigInteger peerExponential;
    BlockCipher cipher;

	public String toString() {
	    StringBuffer sb = new StringBuffer();
	    sb.append(super.toString());
	    sb.append(": myExponent=");
	    sb.append(myExponent.toHexString());
	    sb.append(", myExponential=");
	    sb.append(myExponential.toHexString());
	    if(peerExponential != null) {
	        sb.append(", peerExponential=");
	        sb.append(peerExponential.toHexString());
	    }
	    return sb.toString();
	}
    
    public DiffieHellmanContext(NativeBigInteger myExponent, NativeBigInteger myExponential, DHGroup group) {
        this.myExponent = myExponent;
        this.myExponential = myExponential;
        this.group = group;
        lastUsedTime = System.currentTimeMillis();
    }

    public NativeBigInteger getOurExponential() {
        lastUsedTime = System.currentTimeMillis();
        return myExponential;
    }

    public synchronized BlockCipher getCipher() {
        lastUsedTime = System.currentTimeMillis();
        if(cipher != null) return cipher;
        // Calculate key
        Logger.normal(this, "My exponent: "+myExponent.toHexString()+", my exponential: "+myExponential.toHexString()+", peer's exponential: "+peerExponential.toHexString());
        NativeBigInteger sharedSecret =
            (NativeBigInteger) peerExponential.modPow(myExponent, group.getP());
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        byte[] digest = md.digest(sharedSecret.toByteArray());
        Logger.normal(this, "Key="+HexUtil.bytesToHex(digest));
        try {
            cipher = new Rijndael(256, 256);
        } catch (UnsupportedCipherException e1) {
            throw new Error(e1);
        }
        cipher.initialize(digest);
        return cipher;
    }

    public synchronized void setOtherSideExponential(NativeBigInteger a) {
        lastUsedTime = System.currentTimeMillis();
        if(peerExponential != null) throw new IllegalStateException("Assigned other side exponential twice");
        if(a == null) throw new NullPointerException();
        peerExponential = a;
    }

    long lastUsedTime;
    
    /**
     * @return The time at which this object was last used.
     */
    public long lastUsedTime() {
        return lastUsedTime;
    }

    /**
     * @return True if getCipher() will work. If this returns false, getCipher() will
     * probably NPE.
     */
    public boolean canGetCipher() {
        return peerExponential != null;
    }
}
