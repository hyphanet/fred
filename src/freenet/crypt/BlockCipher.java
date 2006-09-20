package freenet.crypt;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Defines the interface that must be implemented by symmetric block ciphers
 * used in the Freenet cryptography architecture
 */
public interface BlockCipher {

    /**
     * Initializes the cipher context with the given key.  This might entail
     * performing pre-encryption calculation of subkeys, S-Boxes, etc.
     */
    void initialize(byte[] key);
    
    /**
     * Returns the key size, in bits, of the given block-cipher
     */
    int getKeySize();

    /**
     * Returns the block size, in bits, of the given block-cipher
     */
    int getBlockSize();

    /**
     * Enciphers the contents of <b>block</b> where block must be equal
     * to getBlockSize()/8. The result is placed in result and, too has
     * to have length getBlockSize()/8.
     * Block and result may refer to the same array.
     * 
     * Warning: It is not a guarantee that <b>block</b> will not be over-
     * written in the course of the algorithm
     */
    void encipher(byte[] block, byte[] result);

    /**
     * Deciphers the contents of <b>block</b> where block must be equal
     * to getBlockSize()/8. The result is placed in result and, too has
     * to have length getBlockSize()/8.
     * Block and result may refer to the same array.
     * 
     * Warning: It is not a guarantee that <b>block</b> will not be over-
     * written in the course of the algorithm
     */
    void decipher(byte[] block, byte[] result);

}
