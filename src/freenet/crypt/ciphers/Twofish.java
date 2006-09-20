package freenet.crypt.ciphers;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.Logger;
import java.security.InvalidKeyException;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Interfaces with the Twofish AES candidate to implement the Twofish
 * algorithm
 */
public final class Twofish implements BlockCipher {
    private Object sessionKey;
    private int keysize;

    // for Util.getCipherByName..  and yes, screw you too, java
    public Twofish(Integer keysize) throws UnsupportedCipherException {
        this(keysize.intValue());
    }

    public Twofish(int keysize) throws UnsupportedCipherException {
	if (! ((keysize == 64) ||
	       (keysize == 128) ||
	       (keysize == 192) ||
	       (keysize == 256)))
	    throw new UnsupportedCipherException("Invalid keysize");
	this.keysize=keysize;
    }

    public Twofish() {
        this.keysize = 128;
    }

    public final int getBlockSize() {
	return 128;
    }

    public final int getKeySize() {
	return keysize;
    }

    public final void initialize(byte[] key) {
	try {
	    byte[] nkey=new byte[keysize>>3];
	    System.arraycopy(key, 0, nkey, 0, nkey.length);
	    sessionKey=Twofish_Algorithm.makeKey(nkey);
	} catch (InvalidKeyException e) {
	    e.printStackTrace();
	    Logger.error(this,"Invalid key");
	}
    }

    public final void encipher(byte[] block, byte[] result) {
	Twofish_Algorithm.blockEncrypt(block, result, 0, sessionKey);
    }

    public final void decipher(byte[] block, byte[] result) {
	Twofish_Algorithm.blockDecrypt(block, result, 0, sessionKey);
    }
}
