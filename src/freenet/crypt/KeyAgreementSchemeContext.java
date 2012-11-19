/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

public abstract class KeyAgreementSchemeContext {

	protected long lastUsedTime;
	/** The signature of (g^r, grpR) */
    public byte[] signature = null;
	/** The ECDSA signature of ECDH public key */
	public byte[] ecdsaSig = null;
    /** A timestamp: when was the context created ? */
    public final long lifetime = System.currentTimeMillis();

	/**
	* @return The time at which this object was last used.
	*/
	public synchronized long lastUsedTime() {
		return lastUsedTime;
	}
	
	   
    public void setSignature(byte [] sig) {
        this.signature = sig;
    }
	   
    public void setECDSASignature(byte [] sig) {
        this.ecdsaSig = sig;
    }
	
	public byte[] getPublicKeyNetworkFormat() {
		return getPublicKeyNetworkFormat(false);
	}
	public abstract byte[] getPublicKeyNetworkFormat(boolean compressed);
}
