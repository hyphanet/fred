/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.security.MessageDigest;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.crypt.SHA256;
import freenet.support.Fields;

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
    final int hashCode;
    public static final int MAX_LENGTH_BEFORE_COMPRESSION = Integer.MAX_VALUE;
    public static final int TOTAL_HEADERS_LENGTH = 36;
    public static final int DATA_LENGTH = 32768;
    /* Maximum length of compressed payload */
	public static final int MAX_COMPRESSED_DATA_LENGTH = DATA_LENGTH - 4;
    
    @Override
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
    
    public static CHKBlock construct(byte[] data, byte[] header, byte cryptoAlgorithm) throws CHKVerifyException {
    	return new CHKBlock(data, header, null, true, cryptoAlgorithm);
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
            hashCode = key.hashCode() ^ Fields.hashCode(data) ^ Fields.hashCode(headers) ^ cryptoAlgorithm;
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
        SHA256.returnMessageDigest(md);
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
        hashCode = chk.hashCode() ^ Fields.hashCode(data) ^ Fields.hashCode(headers) ^ cryptoAlgorithm;
    }

	@Override
	public NodeCHK getKey() {
        return chk;
    }

	@Override
	public byte[] getRawHeaders() {
		return headers;
	}

	@Override
	public byte[] getRawData() {
		return data;
	}

	@Override
	public byte[] getPubkeyBytes() {
		return null;
	}

	@Override
	public byte[] getFullKey() {
		return getKey().getFullKey();
	}

	@Override
	public byte[] getRoutingKey() {
		return getKey().getRoutingKey();
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof CHKBlock)) return false;
		CHKBlock block = (CHKBlock) o;
		if(!chk.equals(block.chk)) return false;
		if(!Arrays.equals(data, block.data)) return false;
		if(!Arrays.equals(headers, block.headers)) return false;
		if(hashIdentifier != block.hashIdentifier) return false;
		return true;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		/* Storing an SSKBlock is not supported. There are some complications, so lets
		 * not implement this since we don't actually use the functionality atm.
		 * 
		 * The major problems are:
		 * - In both CHKBlock and SSKBlock, who is responsible for deleting the node keys? We
		 *   have to have them in the objects.
		 * - In SSKBlock, who is responsible for deleting the DSAPublicKey? And the DSAGroup?
		 *   A group might be unique or might be shared between very many SSKs...
		 * 
		 * Especially in the second case, we don't want to just copy every time even for
		 * transient uses ... the best solution may be to copy in objectCanNew(), but even
		 * then callers to the relevant getter methods may be a worry.
		 */
		throw new UnsupportedOperationException("Block set storage in database not supported");
	}

}
