/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.IOException;

import freenet.support.api.Bucket;
import freenet.support.io.BucketFactory;

public interface ClientKeyBlock extends KeyBlock {

	/** Decode with the key
	 * @param factory The BucketFactory to use to create the Bucket to return the data in.
	 * @param maxLength The maximum size of the returned data in bytes.
	 */
	Bucket decode(BucketFactory factory, int maxLength, boolean dontDecompress) throws KeyDecodeException, IOException;

	/**
	 * Does the block contain metadata? If not, it contains real data.
	 */
	boolean isMetadata();

    /**
     * @return The ClientKey for this key.
     */
    public ClientKey getClientKey();

}
