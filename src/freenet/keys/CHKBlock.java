/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
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
    /* Maximum length of compressed payload */
	public static final int MAX_COMPRESSED_DATA_LENGTH = DATA_LENGTH - 4;
    
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
    	this(data2, header2, key, key.cryptoAlgorithm);
    }

    public CHKBlock(byte[] data2, byte[] header2, NodeCHK key, byte cryptoAlgorithm) throws CHKVerifyException {
        this(data2, header2, key, true, cryptoAlgorithm);
    }
    
    public CHKBlock(byte[] data2, byte[] header2, NodeCHK key, boolean verify, byte cryptoAlgorithm) throws CHKVerifyException {
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
        	chk = new NodeCHK(hash, cryptoAlgorithm);
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
