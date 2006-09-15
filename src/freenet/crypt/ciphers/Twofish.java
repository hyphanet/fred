/*
  Twofish.java / Freenet, Java Adaptive Network Client
  Copyright (C) Ian Clarke
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.crypt.ciphers;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.Logger;
import java.security.InvalidKeyException;

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
