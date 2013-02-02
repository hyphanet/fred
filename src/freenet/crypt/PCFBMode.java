/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Control mechanism for the Periodic Cipher Feed Back mode.  This is
 * a CFB variant used apparently by a number of programs, including PGP. 
 * Thanks to Hal for suggesting it.
 * 
 * http://www.streamsec.com/pcfb1.pdf
 * 
 * NOTE: This is identical to CFB if block size = key size. As of Freenet 0.7, 
 * we use it with block size = key size. Which is not recommended, but is as 
 * safe as CFB. We will get rid of this eventually, and move to 128-bit block
 * size (i.e. standard AES) with a more standard mode (e.g. CTR or CBC).
 *
 * @author Scott
 */
public class PCFBMode {
    
	/** The underlying block cipher. */
    protected final BlockCipher c;
    /** The register, with which data is XOR'ed */
    protected final byte[] feedback_register;
    /** When this reaches the end of the register, we refillBuffer() i.e. re-encrypt the
     * register. */
    protected int registerPointer;
    
    /** Create the PCFB with no IV. The caller must either:
     * a) Call reset() with a proper IV, or 
     * b) Accept the initial IV of all zero's. (We will still encrypt this before using it).
     * If the key is random and never reused, for instance if it is a one time key or 
     * derived from a hash, b) may be acceptable. It is used in some parts of Freenet. 
     * However, it is very bad practice cryptographically, and we should get rid of it.
     * NOTE THAT IV:KEY PAIRS *MUST* BE UNIQUE! If two instances use the same key with the
     * same empty IV, the bad guys will be able to XOR the two ciphertexts to get the XOR
     * of the plaintext. If they can deduce the register's value they may even be able to
     * decrypt the next 32 bytes, however after that they should hopefully be stumped -
     * but it will certainly make more sophisticated attacks easier.
     * FIXME CRYPTO !!!
     * @param c The underlying block cipher
     * @deprecated
     */
    @Deprecated
    public static PCFBMode create(BlockCipher c) {
    	return new PCFBMode(c);
    }
    
    public static PCFBMode create(BlockCipher c, byte[] iv) {
    	return create(c, iv, 0);
    }
    
    /** Create the PCFB with an IV. The register pointer will be set to the end of the IV,
     * so refillBuffer() will be called prior to any encryption. IV's *must* be unique for
     * a given key. IT IS STRONGLY RECOMMENDED TO USE THIS CONSTRUCTOR, THE OTHER ONE WILL 
     * BE REMOVED EVENTUALLY. 
     * @param offset */
    public static PCFBMode create(BlockCipher c, byte[] iv, int offset) {
    	return new PCFBMode(c, iv, offset);
    }
    
    protected PCFBMode(BlockCipher c) {
        this.c = c;
        feedback_register = new byte[c.getBlockSize() >> 3];
        registerPointer = feedback_register.length;
    }

    protected PCFBMode(BlockCipher c, byte[] iv, int offset) {
        this(c);
        System.arraycopy(iv, offset, feedback_register, 0, feedback_register.length);
        // registerPointer is already set to the end by this(c), so we will refillBuffer() immediately.
    }

    /**
     * Resets the PCFBMode to an initial IV
     */
    public final void reset(byte[] iv) {
        System.arraycopy(iv, 0, feedback_register, 0, feedback_register.length);
        registerPointer = feedback_register.length;
    }
    
    /**
     * Resets the PCFBMode to an initial IV
     * @param iv The buffer containing the IV.
     * @param offset The offset to start reading the IV at.
     */
    public final void reset(byte[] iv, int offset) {
        System.arraycopy(iv, offset, feedback_register, 0, feedback_register.length);
        registerPointer = feedback_register.length;
    }

    /**
     * Writes the initialization vector to the stream.  Though the IV
     * is transmitted in the clear, this gives the attacker no additional 
     * information because the registerPointer is set so that the encrypted
     * buffer is empty.  This causes an immediate encryption of the IV,
     * thus invalidating any information that the attacker had.
     */
    public void writeIV(RandomSource rs, OutputStream out) throws IOException {
        rs.nextBytes(feedback_register);
        out.write(feedback_register);
    }
    
    /**
     * Reads the initialization vector from the given stream.  
     */
    public void readIV(InputStream in) throws IOException {
        //for (int i=0; i<feedback_register.length; i++) {
        //    feedback_register[i]=(byte)in.read();
        //}
        Util.readFully(in, feedback_register);
    }

    /**
     * returns the length of the IV
     */
    public int lengthIV() {
        return feedback_register.length;
    }

    /**
     * returns the length of the IV for a PCFB created with a specific cipher.
     */
	public static int lengthIV(BlockCipher c) {
		return c.getBlockSize() >> 3;
	}

    /**
     * Deciphers one byte of data, by XOR'ing the ciphertext byte with
     * one byte from the encrypted buffer.  Then places the received
     * byte in the feedback register.  If no bytes are available in 
     * the encrypted buffer, the feedback register is encrypted, providing
     * block_size/8 new bytes for decryption
     */
    //public synchronized int decipher(int b) {
    public int decipher(int b) {
        if (registerPointer == feedback_register.length) refillBuffer();
        int rv = (feedback_register[registerPointer] ^ (byte) b) & 0xff;
        feedback_register[registerPointer++] = (byte) b;
        return rv;
    }

    /**
     * NOTE: As a side effect, this will decrypt the data in the array.
     */
    //public synchronized byte[] blockDecipher(byte[] buf, int off, int len) {
    public byte[] blockDecipher(byte[] buf, int off, int len) {
		final int feedback_length = feedback_register.length;
		if (registerPointer != 0) {
			/* handle first incomplete feedback run */
			int l = Math.min(feedback_length - registerPointer, len);
			len -= l;
			while(l-- > 0) {
                byte b = buf[off];
                buf[off++] ^= feedback_register[registerPointer];
                feedback_register[registerPointer++] = b;
            }
			if (len == 0) return buf;
			refillBuffer();
		}
		// assert(registerPointer == 0);
        while (len > feedback_length) {
			/* consume full blocks */
			// note: we skip *last* full block to avoid extra refillBuffer()
			len -= feedback_length;
			while (registerPointer < feedback_length) {
                byte b = buf[off];
                buf[off++] ^= feedback_register[registerPointer];
                feedback_register[registerPointer++] = b;
            }
			refillBuffer();
        }
		// assert(registerPointer == 0 && len <= feedback_length);
		while (len-- > 0) {
			/* handle final block */
			byte b = buf[off];
			buf[off++] ^= feedback_register[registerPointer];
			feedback_register[registerPointer++] = b;
		}
        return buf;
    }

    /**
     * Enciphers one byte of data, by XOR'ing the plaintext byte with
     * one byte from the encrypted buffer.  Then places the enciphered 
     * byte in the feedback register.  If no bytes are available in 
     * the encrypted buffer, the feedback register is encrypted, providing
     * block_size/8 new bytes for encryption
     */
    //public synchronized int encipher(int b) {
    public int encipher(int b) {
        if (registerPointer == feedback_register.length) refillBuffer();
        feedback_register[registerPointer] ^= (byte) b;
        return feedback_register[registerPointer++] & 0xff;
    }

    /**
     * NOTE: As a sideeffect, this will encrypt the data in the array.
     */
    //public synchronized byte[] blockEncipher(byte[] buf, int off, int len) {
    public byte[] blockEncipher(byte[] buf, int off, int len) {
		final int feedback_length = feedback_register.length;
		if (registerPointer != 0) {
			/* handle first incomplete feedback run */
			int l = Math.min(feedback_length - registerPointer, len);
			for(len -= l; l-- > 0; off++)
                buf[off] = (feedback_register[registerPointer++] ^= buf[off]);
			if (len == 0) return buf;
			refillBuffer();
		}
		// assert(registerPointer == 0);
        while (len > feedback_length) {
			/* consume full blocks */
			// note: we skip *last* full block to avoid extra refillBuffer()
			len -= feedback_length;
			for (; registerPointer < feedback_length; off++)
                buf[off] = (feedback_register[registerPointer++] ^= buf[off]);
            refillBuffer();
        }
		// assert(registerPointer == 0 && len <= feedback_length);
		for (; len-- > 0; off++) {
			/* handle final partial block */
			buf[off] = (feedback_register[registerPointer++] ^= buf[off]);
		}
        return buf;
    }
        
    // Refills the encrypted buffer with data.
    //private synchronized void refillBuffer() {
    protected void refillBuffer() {
        // Encrypt feedback into result
        c.encipher(feedback_register, feedback_register);

        registerPointer=0;
    }

}
