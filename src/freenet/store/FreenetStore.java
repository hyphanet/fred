/*
  FreenetStore.java / Freenet
  Copyright (C) 2004,2005 Change.Tv, Inc
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

package freenet.store;

import java.io.IOException;

import com.sleepycat.je.DatabaseException;

import freenet.crypt.DSAPublicKey;
import freenet.keys.CHKBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;

/**
 * Datastore interface
 */
public interface FreenetStore {

    /**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK key, boolean dontPromote) throws IOException;

    /**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public SSKBlock fetch(NodeSSK key, boolean dontPromote) throws IOException;

    /**
     * Fetch a public key.
     */
    public DSAPublicKey fetchPubKey(byte[] hash, boolean dontPromote) throws IOException;
    
    /**
     * Store a block.
     * @throws KeyCollisionException If the key already exists but has different contents.
     * @param ignoreAndOverwrite If true, overwrite old content rather than throwing a KeyCollisionException. 
     */
    public void put(SSKBlock block, boolean ignoreAndOverwrite) throws IOException, KeyCollisionException;

    /**
     * Store a block.
     */
    public void put(CHKBlock block) throws IOException;
    
    /**
     * Store a public key.
     */
    public void put(byte[] hash, DSAPublicKey key) throws IOException;

    /**
     * Change the store size.
     * @param maxStoreKeys The maximum number of keys to be cached.
     * @param shrinkNow If false, don't shrink the store immediately.
     * @throws IOException 
     * @throws DatabaseException 
     */
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow) throws DatabaseException, IOException;
	
	public long hits();
	
	public long misses();

	public long keyCount();
}
