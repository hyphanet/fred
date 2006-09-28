/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.DSAPublicKey;

/**
 * Interface for a DSA public key lookup service.
 */
public interface DSAPublicKeyDatabase {
	
	/**
	 * Lookup a key by its hash.
	 * @param hash
	 * @return The key, or null.
	 */
	public DSAPublicKey lookupKey(byte[] hash);

}
