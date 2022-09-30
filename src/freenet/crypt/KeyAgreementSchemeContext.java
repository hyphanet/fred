/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

public abstract class KeyAgreementSchemeContext {

	protected long lastUsedTime;

    /** ECDSA signature. Used by negType 9+. */
    public byte[] ecdsaSig;
    /** A timestamp: when was the context created ? */
    public final long lifetime = System.currentTimeMillis();

	/**
	* @return The time at which this object was last used.
	*/
	public synchronized long lastUsedTime() {
		return lastUsedTime;
	}
	  
    public void setECDSASignature(byte[] sig) {
        this.ecdsaSig = sig;
    }

	public abstract byte[] getPublicKeyNetworkFormat();
}