/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.store.StorableBlock;

/**
 * Interface for fetched blocks. Can be decoded with a key.
 */
public interface KeyBlock extends StorableBlock {

    final static int HASH_SHA256 = 1;
	
    public Key getKey();
    public byte[] getRawHeaders();
    public byte[] getRawData();
	public byte[] getPubkeyBytes();
	public void removeFrom(ObjectContainer container);

}
