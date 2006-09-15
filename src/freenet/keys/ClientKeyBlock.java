/*
  ClientKeyBlock.java / Freenet
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

package freenet.keys;

import java.io.IOException;

import freenet.support.io.Bucket;
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
