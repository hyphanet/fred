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
    final static int HASH_SHA256 = 1;
    
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
        if(header.length < 2) throw new IllegalArgumentException("Too short: "+header.length);
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

    /**
     * Decode into RAM, if short.
     * @throws CHKDecodeException 
     */
	public byte[] memoryDecode(ClientCHK chk) throws CHKDecodeException {
		try {
			ArrayBucket a = (ArrayBucket) decode(chk, new ArrayBucketFactory(), 32*1024);
			return BucketTools.toByteArray(a); // FIXME
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	
    public Bucket decode(ClientKey key, BucketFactory bf, int maxLength) throws KeyDecodeException, IOException {
    	if(!(key instanceof ClientCHK))
    		throw new KeyDecodeException("Not a CHK!: "+key);
    	return decode((ClientCHK)key, bf, maxLength);
    }
    
    /**
     * Decode the CHK and recover the original data
     * @return the original data
     * @throws IOException If there is a bucket error.
     */
    public Bucket decode(ClientCHK key, BucketFactory bf, int maxLength) throws CHKDecodeException, IOException {
        // Overall hash already verified, so first job is to decrypt.
        if(key.cryptoAlgorithm != ClientCHK.ALGO_AES_PCFB_256)
            throw new UnsupportedOperationException();
        BlockCipher cipher;
        try {
            cipher = new Rijndael(256, 256);
        } catch (UnsupportedCipherException e) {
            // FIXME - log this properly
            throw new Error(e);
        }
        byte[] cryptoKey = key.cryptoKey;
        if(cryptoKey.length < Node.SYMMETRIC_KEY_LENGTH)
            throw new CHKDecodeException("Crypto key too short");
        cipher.initialize(key.cryptoKey);
        PCFBMode pcfb = new PCFBMode(cipher);
        byte[] hbuf = new byte[header.length-2];
        System.arraycopy(header, 2, hbuf, 0, header.length-2);
        byte[] dbuf = new byte[data.length];
        System.arraycopy(data, 0, dbuf, 0, data.length);
        // Decipher header first - functions as IV
        pcfb.blockDecipher(hbuf, 0, hbuf.length);
        pcfb.blockDecipher(dbuf, 0, dbuf.length);
        // Check: Decryption key == hash of data (not including header)
        MessageDigest md256;
        try {
            md256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            // FIXME: log this properly?
            throw new Error(e1);
        }
        byte[] dkey = md256.digest(dbuf);
        if(!java.util.Arrays.equals(dkey, key.cryptoKey)) {
            throw new CHKDecodeException("Check failed: decrypt key == H(data)");
        }
        // Check: IV == hash of decryption key
        byte[] predIV = md256.digest(dkey);
        // Extract the IV
        byte[] iv = new byte[32];
        System.arraycopy(hbuf, 0, iv, 0, 32);
        if(!Arrays.equals(iv, predIV))
            throw new CHKDecodeException("Check failed: Decrypted IV == H(decryption key)");
        // Checks complete
        int size = ((hbuf[32] & 0xff) << 8) + (hbuf[33] & 0xff);
        if(size > 32768 || size < 0)
            throw new CHKDecodeException("Invalid size: "+size);
        byte[] output = new byte[size];
        // No particular reason to check the padding, is there?
        System.arraycopy(dbuf, 0, output, 0, size);
        return decompress(key, output, bf, maxLength);
    }

    private Bucket decompress(ClientCHK key, byte[] output, BucketFactory bf, int maxLength) throws CHKDecodeException, IOException {
        if(key.isCompressed()) {
        	Logger.minor(this, "Decompressing in decode: "+key.getURI()+" with codec "+key.compressionAlgorithm);
            if(output.length < 5) throw new CHKDecodeException("No bytes to decompress");
            // Decompress
            // First get the length
            int len = ((((((output[0] & 0xff) << 8) + (output[1] & 0xff)) << 8) + (output[2] & 0xff)) << 8) +
            	(output[3] & 0xff);
            if(len > MAX_LENGTH_BEFORE_COMPRESSION)
                throw new CHKDecodeException("Invalid precompressed size: "+len);
            Compressor decompressor = Compressor.getCompressionAlgorithmByMetadataID(key.compressionAlgorithm);
            Bucket inputBucket = new SimpleReadOnlyArrayBucket(output, 4, output.length-4);
            try {
				return decompressor.decompress(inputBucket, bf, maxLength);
			} catch (CompressionOutputSizeException e) {
				throw new CHKDecodeException("Too big");
			}
        } else {
        	return BucketTools.makeImmutableBucket(bf, output);
        }
	}

	public Key getKey() {
        return chk;
    }
}
