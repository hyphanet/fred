/*
  Rijndael.java / Freenet, Java Adaptive Network Client
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

import java.security.InvalidKeyException;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * Interfaces with the Rijndael AES candidate to implement the Rijndael
 * algorithm
 */
public class Rijndael implements BlockCipher {
    private Object sessionKey;
    private int keysize, blocksize;

    // for Util.getCipherByName..  and yes, screw you too, java
    public Rijndael(Integer keysize) throws UnsupportedCipherException {
        this(keysize.intValue());
    }

    public Rijndael(int keysize) throws UnsupportedCipherException {
	this(keysize, 128);
    }

    public Rijndael(int keysize, int blocksize) throws UnsupportedCipherException {
	if (! ((keysize == 128) ||
	       (keysize == 192) ||
	       (keysize == 256)))
	    throw new UnsupportedCipherException("Invalid keysize");
	if (! ((blocksize == 128) ||
	       (blocksize == 192) ||
	       (blocksize == 256)))
	    throw new UnsupportedCipherException("Invalid blocksize");
	this.keysize=keysize;
	this.blocksize=blocksize;
    }

    public Rijndael() {
        this.keysize   = 128;
        this.blocksize = 128;
    }

    public final int getBlockSize() {
	return blocksize;
    }

    public final int getKeySize() {
	return keysize;
    }

    public final void initialize(byte[] key) {
	try {
	    byte[] nkey=new byte[keysize>>3];
	    System.arraycopy(key, 0, nkey, 0, nkey.length);
	    sessionKey=Rijndael_Algorithm.makeKey(nkey);
	} catch (InvalidKeyException e) {
	    e.printStackTrace();
	    Logger.error(this,"Invalid key");
	}
    }

    public synchronized final void encipher(byte[] block, byte[] result) {
        if(block.length != blocksize/8)
            throw new IllegalArgumentException();
	Rijndael_Algorithm.blockEncrypt(block, result, 0, sessionKey);
    }

    public synchronized final void decipher(byte[] block, byte[] result) {
        if(block.length != blocksize/8)
            throw new IllegalArgumentException();
	Rijndael_Algorithm.blockDecrypt(block, result, 0, sessionKey);
    }

    public static void main(String[] args) throws UnsupportedCipherException {
	// Perform the Monte Carlo test

	System.out.println("KEYSIZE=128\n");
	monteCarlo(128);
	System.out.println("=========================\n");
	System.out.println("KEYSIZE=192\n");
	monteCarlo(192);
	System.out.println("=========================\n");
	System.out.println("KEYSIZE=256\n");
	monteCarlo(256);
    }

    static void monteCarlo(int keySize) throws UnsupportedCipherException {
	Rijndael ctx=new Rijndael(keySize);
	int kb=keySize/8;
	byte[] P=new byte[16], C=new byte[16], 
	    CL=new byte[16], KEY=new byte[kb];

	for (int i=0; i<400; i++) {
	    System.out.println("I="+i);
	    System.out.println("KEY="+HexUtil.bytesToHex(KEY,0,kb));

	    System.out.println("PT="+HexUtil.bytesToHex(P,0,16));

	    ctx.initialize(KEY);
	    for (int j=0; j<10000; j++) {
		System.arraycopy(C, 0, CL, 0, C.length);
		ctx.encipher(P, C);
		System.arraycopy(C, 0, P, 0, P.length);
	    }
	    System.out.println("CT="+HexUtil.bytesToHex(C,0,16));

	    
	    for (int x=0; x<kb; x++) {
		if (keySize==192)
		    if (x<8)
			KEY[x]^=CL[8+x];
		    else 
			KEY[x]^=C[x-8];
		else if (keySize==256)
		    if (x<16)
			KEY[x]^=CL[x];
		    else 
			KEY[x]^=C[x-16];
		else KEY[x]^=C[x];
	    }

	    if (keySize==192) 
		for (int x=0; x<8; x++) 
		    KEY[x+16]^=CL[x+8];
	    else if (keySize==256) 
		for (int x=0; x<16; x++) 
		    KEY[x+16]^=CL[x];

	    System.out.println();
	}
    }
}








