/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

public interface Digest {

    /**
     * retrieve the value of a hash, by filling the provided int[] with
     * n elements of the hash (where n is the bitlength of the hash/32)
     * @param digest int[] into which to place n elements
     * @param offset index of first of the n elements
     */
    public void extract(int [] digest, int offset);

     /**
     * Add one byte to the digest. When this is implemented
     * all of the abstract class methods end up calling
     * this method for types other than bytes.
     * @param b byte to add
     */
    public void update(byte b);

    /**
     * Add many bytes to the digest.
     * @param data byte data to add
     * @param offset start byte
     * @param length number of bytes to hash
     */
    public void update(byte[] data, int offset, int length);

    /**
     * Adds the entire contents of the byte array to the digest.
     */
    public void update(byte[] data);
     
    /**
     * Returns the completed digest, reinitializing the hash function.
     * @return the byte array result
     */
    public byte[] digest();

    /**
     * Write completed digest into the given buffer.
     * @param buffer the buffer to write into
     * @param offset the byte offset at which to start writing
     * @param reset If true, the hash function is reinitialized
     * after writing to the buffer.
     */
    public void digest(boolean reset, byte[] buffer, int offset);

    /**
     * Return the hash size of this digest in bits
     */
    public int digestSize();
}




