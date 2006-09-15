/*
  CHKBlock.java / Freenet
  Copyright (C) amphibian
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

import java.security.MessageDigest;

import freenet.crypt.SHA256;

/**
 * @author amphibian
 * 
 * CHK plus data. When fed a ClientCHK, can decode into the original
 * data for a client.
 */
public class CHKBlock implements KeyBlock {

    final byte[] data;
    final byte[] headers;
    final short hashIdentifier;
    final NodeCHK chk;
    public static final int MAX_LENGTH_BEFORE_COMPRESSION = Integer.MAX_VALUE;
    public static final int TOTAL_HEADERS_LENGTH = 36;
    public static final int DATA_LENGTH = 32768;
    
    public String toString() {
        return super.toString()+": chk="+chk;
    }
    
    /**
     * @return The header for this key. DO NOT MODIFY THIS DATA!
     */
    public byte[] getHeaders() {
        return headers;
    }

    /**
     * @return The actual data for this key. DO NOT MODIFY THIS DATA!
     */
    public byte[] getData() {
        return data;
    }

    public CHKBlock(byte[] data2, byte[] header2, NodeCHK key) throws CHKVerifyException {
        this(data2, header2, key, true);
    }
    
    public CHKBlock(byte[] data2, byte[] header2, NodeCHK key, boolean verify) throws CHKVerifyException {
        data = data2;
        headers = header2;
        if(headers.length != TOTAL_HEADERS_LENGTH)
        	throw new IllegalArgumentException("Wrong length: "+headers.length+" should be "+TOTAL_HEADERS_LENGTH);
        hashIdentifier = (short)(((headers[0] & 0xff) << 8) + (headers[1] & 0xff));
//        Logger.debug(CHKBlock.class, "Data length: "+data.length+", header length: "+header.length);
        if((key != null) && !verify) {
        	this.chk = key;
        	return;
        }
        
        // Minimal verification
        // Check the hash
        if(hashIdentifier != HASH_SHA256)
            throw new CHKVerifyException("Hash not SHA-256");
        MessageDigest md = SHA256.getMessageDigest();
        
        md.update(headers);
        md.update(data);
        byte[] hash = md.digest();
        if(key == null) {
        	chk = new NodeCHK(hash);
        } else {
        	chk = key;
            byte[] check = chk.routingKey;
            if(!java.util.Arrays.equals(hash, check)) {
                throw new CHKVerifyException("Hash does not verify");
            }
            // Otherwise it checks out
        }
    }

	public Key getKey() {
        return chk;
    }

	public byte[] getRawHeaders() {
		return headers;
	}

	public byte[] getRawData() {
		return data;
	}
}
