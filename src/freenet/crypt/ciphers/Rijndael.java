package freenet.crypt.ciphers;

import java.security.InvalidKeyException;

import freenet.crypt.BlockCipher;
import freenet.crypt.UnsupportedCipherException;
import freenet.support.HexUtil;
import freenet.support.Logger;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Interfaces with the Rijndael AES candidate to implement the Rijndael
 * algorithm
 */
public class Rijndael implements BlockCipher {
    private Object sessionKey;
    private final int keysize, blocksize;
    // FIXME remove. This is used to simulate an old security threatening bug in order
    // to have backwards compatibility with old keys.
    private final int cryptBlockSize;

    // for Util.getCipherByName..  and yes, screw you too, java
    public Rijndael(Integer keysize) throws UnsupportedCipherException {
        this(keysize.intValue());
    }

    public Rijndael(int keysize) throws UnsupportedCipherException {
	this(keysize, 128, false);
    }

    /**
     * Create a Rijndael instance.
     * @param keysize The key size.
     * @param blocksize The block size.
     * @param fakeInsecure If true, only encrypt the first 128 bits of any block. This
     * is insecure! It is used for backwards compatibility with old data encrypted with
     * the old code.
     * @throws UnsupportedCipherException
     */
    public Rijndael(int keysize, int blocksize, boolean fakeInsecure) throws UnsupportedCipherException {
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
	// FIXME This is deliberate insecurity! It is used for backwards compatibility *ONLY*!
	// FIXME IT MUST BE REMOVED SOON!
	if(fakeInsecure)
		this.cryptBlockSize = 128;
	else
		this.cryptBlockSize = blocksize;
    }

    public Rijndael() {
        this.keysize   = 128;
        this.blocksize = 128;
        this.cryptBlockSize = 128;
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
	    sessionKey=Rijndael_Algorithm.makeKey(nkey, cryptBlockSize/8);
	} catch (InvalidKeyException e) {
	    e.printStackTrace();
	    Logger.error(this,"Invalid key");
	}
    }

    public synchronized final void encipher(byte[] block, byte[] result) {
        if(block.length != blocksize/8)
            throw new IllegalArgumentException();
	Rijndael_Algorithm.blockEncrypt(block, result, 0, sessionKey, cryptBlockSize/8);
    }

    public synchronized final void decipher(byte[] block, byte[] result) {
        if(block.length != blocksize/8)
            throw new IllegalArgumentException();
	Rijndael_Algorithm.blockDecrypt(block, result, 0, sessionKey, cryptBlockSize/8);
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








