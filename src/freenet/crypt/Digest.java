/*
  Digest.java / Freenet, Java Adaptive Network Client
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




