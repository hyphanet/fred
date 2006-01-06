package freenet.keys;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.node.Node;
import freenet.support.ArrayBucket;
import freenet.support.ArrayBucketFactory;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressException;

/**
 * @author amphibian
 * 
 * CHK plus data. When fed a ClientCHK, can decode into the original
 * data for a client.
 */
public class CHKBlock implements KeyBlock {

    final byte[] data;
    final byte[] header;
    final short hashIdentifier;
    final NodeCHK chk;
    public static final int MAX_LENGTH_BEFORE_COMPRESSION = Integer.MAX_VALUE;
    public static final int TOTAL_HEADERS_LENGTH = 36;
    
    public String toString() {
        return super.toString()+": chk="+chk;
    }
    
    /**
     * @return The header for this key. DO NOT MODIFY THIS DATA!
     */
    public byte[] getHeader() {
        return header;
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
        header = header2;
        if(header.length != TOTAL_HEADERS_LENGTH)
        	throw new IllegalArgumentException("Wrong length: "+header.length+" should be "+TOTAL_HEADERS_LENGTH);
        hashIdentifier = (short)(((header[0] & 0xff) << 8) + (header[1] & 0xff));
        this.chk = key;
//        Logger.debug(CHKBlock.class, "Data length: "+data.length+", header length: "+header.length);
        if(!verify) return;
        
        // Minimal verification
        // Check the hash
        if(hashIdentifier != HASH_SHA256)
            throw new CHKVerifyException("Hash not SHA-256");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        md.update(header);
        md.update(data);
        byte[] hash = md.digest();
        byte[] check = chk.routingKey;
        if(!java.util.Arrays.equals(hash, check)) {
            throw new CHKVerifyException("Hash does not verify");
        }
        // Otherwise it checks out
    }

	public Key getKey() {
        return chk;
    }
}
