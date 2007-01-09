/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import freenet.crypt.ciphers.Rijndael;

public abstract class KeyAgreementSchemeContext {
    BlockCipher cipher;
    byte[] key;
	
    protected long lastUsedTime;
    protected boolean logMINOR;
    
    /**
     * @return The time at which this object was last used.
     */
    public synchronized long lastUsedTime() {
        return lastUsedTime;
    }
    
    public abstract byte[] getKey();
    public abstract boolean canGetCipher();
    
    public synchronized BlockCipher getCipher() {
        lastUsedTime = System.currentTimeMillis();
        if(cipher != null) return cipher;
        getKey();
        try {
            cipher = new Rijndael(256, 256, false);
        } catch (UnsupportedCipherException e1) {
            throw new Error(e1);
        }
        cipher.initialize(key);
        return cipher;
    }
}
