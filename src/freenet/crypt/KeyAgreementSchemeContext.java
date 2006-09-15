/*
  KeyAgreementSchemeContext.java / Freenet
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
            cipher = new Rijndael(256, 256);
        } catch (UnsupportedCipherException e1) {
            throw new Error(e1);
        }
        cipher.initialize(key);
        return cipher;
    }
}
