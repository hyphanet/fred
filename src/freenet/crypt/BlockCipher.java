/*
  BlockCipher.java / Freenet, Java Adaptive Network Client
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
