package freenet.keys;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.ArrayBucket;
import freenet.support.ArrayBucketFactory;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.compress.DecompressException;


/**
 * @author amphibian
 * 
 * Client CHKBlock - provides functions for decoding, holds a key.
 */
public class ClientCHKBlock extends CHKBlock {

    public static final long MAX_COMPRESSED_DATA_LENGTH = NodeCHK.BLOCK_SIZE - 4;
	final ClientCHK key;
    
    public String toString() {
        return super.toString()+",key="+key;
    }
    
    /**
     * Construct from data retrieved, and a key.
     * Do not do full decode. Verify what can be verified without doing
     * a full decode.
     * @param key2 The client key.
     * @param header The header.
     * @param data The data.
     */
    public ClientCHKBlock(byte[] data, byte[] header, ClientCHK key2, boolean verify) throws CHKVerifyException {
        super(data, header, key2.getNodeCHK(), verify);
        this.key = key2;
    }

    /**
     * Construct from a CHKBlock and a key.
     */
    public ClientCHKBlock(CHKBlock block, ClientCHK key2) throws CHKVerifyException {
        this(block.getData(), block.getHeader(), key2, true);
    }

    /**
     * Encode a Bucket of data to a CHKBlock.
     * @param sourceData The bucket of data to encode. Can be arbitrarily large.
     * @param asMetadata Is this a metadata key?
     * @param dontCompress If set, don't even try to compress.
     * @param alreadyCompressedCodec If !dontCompress, and this is >=0, then the
     * data is already compressed, and this is the algorithm.
     * @throws CHKEncodeException
     * @throws IOException If there is an error reading from the Bucket.
     */
    static public ClientCHKBlock encode(Bucket sourceData, boolean asMetadata, boolean dontCompress, short alreadyCompressedCodec, long sourceLength) throws CHKEncodeException, IOException {
        byte[] finalData = null;
        byte[] data;
        byte[] header;
        ClientCHK key;
        short compressionAlgorithm = -1;
        // Try to compress it - even if it fits into the block,
        // because compressing it improves its entropy.
        boolean compressed = false;
        if(sourceData.size() > MAX_LENGTH_BEFORE_COMPRESSION)
            throw new CHKEncodeException("Too big");
        if(!dontCompress) {
        	byte[] cbuf = null;
        	if(alreadyCompressedCodec >= 0) {
           		if(sourceData.size() > MAX_COMPRESSED_DATA_LENGTH)
        			throw new CHKEncodeException("Too big (precompressed)");
        		compressionAlgorithm = alreadyCompressedCodec;
        		cbuf = BucketTools.toByteArray(sourceData);
        		if(sourceLength > MAX_LENGTH_BEFORE_COMPRESSION)
        			throw new CHKEncodeException("Too big");
        	} else {
        		if (sourceData.size() > NodeCHK.BLOCK_SIZE) {
					// Determine the best algorithm
					for (int i = 0; i < Compressor.countCompressAlgorithms(); i++) {
						Compressor comp = Compressor
								.getCompressionAlgorithmByDifficulty(i);
						ArrayBucket compressedData;
						try {
							compressedData = (ArrayBucket) comp.compress(
									sourceData, new ArrayBucketFactory(), NodeCHK.BLOCK_SIZE);
						} catch (IOException e) {
							throw new Error(e);
						} catch (CompressionOutputSizeException e) {
							continue;
						}
						if (compressedData.size() <= MAX_COMPRESSED_DATA_LENGTH) {
							compressionAlgorithm = comp
									.codecNumberForMetadata();
							sourceLength = sourceData.size();
							try {
								cbuf = BucketTools.toByteArray(compressedData);
								// FIXME provide a method in ArrayBucket
							} catch (IOException e) {
								throw new Error(e);
							}
							break;
						}
					}
				}
        		
        	}
        	if(cbuf != null) {
    			// Use it
    			int compressedLength = cbuf.length;
                finalData = new byte[compressedLength+4];
                System.arraycopy(cbuf, 0, finalData, 4, compressedLength);
                finalData[0] = (byte) ((sourceLength >> 24) & 0xff);
                finalData[1] = (byte) ((sourceLength >> 16) & 0xff);
                finalData[2] = (byte) ((sourceLength >> 8) & 0xff);
                finalData[3] = (byte) ((sourceLength) & 0xff);
                compressed = true;
        	}
        }
        if(finalData == null) {
            if(sourceData.size() > NodeCHK.BLOCK_SIZE) {
                throw new CHKEncodeException("Too big");
            }
        	finalData = BucketTools.toByteArray(sourceData);
        }
        
        // Now do the actual encode
        
        MessageDigest md160;
        try {
            md160 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e2) {
            // FIXME: log properly? Not much we can do... But we could log a
            // more user-friendly message...
            throw new Error(e2);
        }
        MessageDigest md256;
        try {
            md256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            // FIXME: log this properly?
            throw new Error(e1);
        }
        // First pad it
        if(finalData.length != 32768) {
            // Hash the data
            if(finalData.length != 0)
            	md256.update(finalData);
            byte[] digest = md256.digest();
            // Turn digest into a seed array for the MT
            int[] seed = new int[8]; // 32/4=8
            for(int i=0;i<8;i++) {
                int x = digest[i*4] & 0xff;
                x = (x << 8) + (digest[i*4+1] & 0xff);
                x = (x << 8) + (digest[i*4+2] & 0xff);
                x = (x << 8) + (digest[i*4+3] & 0xff);
                seed[i] = x;
            }
            MersenneTwister mt = new MersenneTwister(seed);
            data = new byte[32768];
            System.arraycopy(finalData, 0, data, 0, finalData.length);
            byte[] randomBytes = new byte[32768-finalData.length];
            mt.nextBytes(randomBytes);
            System.arraycopy(randomBytes, 0, data, finalData.length, 32768-finalData.length);
        } else {
        	data = finalData;
        }
        // Now make the header
        byte[] encKey = md256.digest(data);
        md256.reset();
        // IV = E(H(crypto key))
        byte[] plainIV = md256.digest(encKey);
        header = new byte[plainIV.length+2+2];
        header[0] = (byte)(CHKBlock.HASH_SHA1 >> 8);
        header[1] = (byte)(CHKBlock.HASH_SHA1 & 0xff);
        System.arraycopy(plainIV, 0, header, 2, plainIV.length);
        header[plainIV.length+2] = (byte)(finalData.length >> 8);
        header[plainIV.length+3] = (byte)(finalData.length & 0xff);
        // GRRR, java 1.4 does not have any symmetric crypto
        // despite exposing asymmetric and hashes!
        
        // Now encrypt the header, then the data, using the same PCFB instance
        BlockCipher cipher;
        try {
            cipher = new Rijndael(256, 256);
        } catch (UnsupportedCipherException e) {
            // FIXME - log this properly
            throw new Error(e);
        }
        cipher.initialize(encKey);
        PCFBMode pcfb = new PCFBMode(cipher);
        pcfb.blockEncipher(header, 2, header.length-2);
        pcfb.blockEncipher(data, 0, data.length);
        
        // Now calculate the final hash
        md160.update(header);
        byte[] finalHash = md160.digest(data);
        
        // Now convert it into a ClientCHK
        key = new ClientCHK(finalHash, encKey, asMetadata, ClientCHK.ALGO_AES_PCFB_256, compressionAlgorithm);
        
        try {
            return new ClientCHKBlock(data, header, key, false);
        } catch (CHKVerifyException e3) {
            //WTF?
            throw new Error(e3);
        }
    }
    
    /**
     * Encode a block of data to a CHKBlock.
     * @param sourceData The data to encode.
     * @param asMetadata Is this a metadata key?
     * @param dontCompress If set, don't even try to compress.
     * @param alreadyCompressedCodec If !dontCompress, and this is >=0, then the
     * data is already compressed, and this is the algorithm.
     */
    static public ClientCHKBlock encode(byte[] sourceData, boolean asMetadata, boolean dontCompress, short alreadyCompressedCodec, int sourceLength) throws CHKEncodeException {
    	try {
			return encode(new ArrayBucket(sourceData), asMetadata, dontCompress, alreadyCompressedCodec, sourceLength);
		} catch (IOException e) {
			// Can't happen
			throw new Error(e);
		}
    }

    /**
     * @return The ClientCHK for this key.
     */
    public ClientCHK getClientKey() {
        return key;
    }

}
