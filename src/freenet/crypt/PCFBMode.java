package freenet.crypt;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/
import java.io.*;

/**
 * Control mechanism for the Periodic Cipher Feed Back mode.  This is
 * a CFB variant used apparently by a number of programs, including PGP. 
 * Thanks to Hal for suggesting it.
 *
 * @author Scott
 */
public class PCFBMode {
    
    private BlockCipher c;
    private byte[] feedback_register;
    private int registerPointer;
    
    public PCFBMode(BlockCipher c) {
        this.c = c;
        feedback_register = new byte[c.getBlockSize() >> 3];
        registerPointer = feedback_register.length;
    }

    public PCFBMode(BlockCipher c, byte[] iv) {
        this(c);
        System.arraycopy(iv, 0, feedback_register, 0, feedback_register.length);
    }

    /**
     * Resets the PCFBMode to an initial IV
     */
    public final void reset(byte[] iv) {
        System.arraycopy(iv, 0, feedback_register, 0, feedback_register.length);
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
     * NOTE: As a sideeffect, this will decrypt the data in the array.
     */
    //public synchronized byte[] blockDecipher(byte[] buf, int off, int len) {
    public byte[] blockDecipher(byte[] buf, int off, int len) {
        while (len > 0) {
            if (registerPointer == feedback_register.length) refillBuffer();
            int n = Math.min(len, feedback_register.length - registerPointer);
            for (int i=off; i<off+n; ++i) {
                byte b = buf[i];
                buf[i] ^= feedback_register[registerPointer];
                feedback_register[registerPointer++] = b;
            }
            off += n;
            len -= n;
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
        while (len > 0) {
            if (registerPointer == feedback_register.length) refillBuffer();
            int n = Math.min(len, feedback_register.length - registerPointer);
            for (int i=off; i<off+n; ++i)
                buf[i] = (feedback_register[registerPointer++] ^= buf[i]);
            off += n;
            len -= n;
        }
        return buf;
    }
        
    // Refills the encrypted buffer with data.
    //private synchronized void refillBuffer() {
    private void refillBuffer() {
        // Encrypt feedback into result
        c.encipher(feedback_register, feedback_register);

        registerPointer=0;
    }
}



