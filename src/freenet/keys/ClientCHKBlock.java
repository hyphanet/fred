/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.Key.Compressed;
import freenet.node.Node;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketFactory;
import freenet.support.io.BucketTools;

/**
 * @author amphibian
 * 
 * Client CHKBlock - provides functions for decoding, holds a client-key.
 */
public class ClientCHKBlock extends CHKBlock implements ClientKeyBlock {

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
        this(block.getData(), block.getHeaders(), key2, true);
    }

    /**
     * Decode into RAM, if short.
     * @throws CHKDecodeException 
     */
	public byte[] memoryDecode() throws CHKDecodeException {
		try {
			ArrayBucket a = (ArrayBucket) decode(new ArrayBucketFactory(), 32*1024, false);
			return BucketTools.toByteArray(a); // FIXME
		} catch (IOException e) {
			throw new Error(e);
		}
	}

    /**
     * Decode the CHK and recover the original data
     * @return the original data
     * @throws IOException If there is a bucket error.
     */
    public Bucket decode(BucketFactory bf, int maxLength, boolean dontCompress) throws CHKDecodeException, IOException {
        // Overall hash already verified, so first job is to decrypt.
        if(key.cryptoAlgorithm != Key.ALGO_AES_PCFB_256_SHA256)
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
        byte[] hbuf = new byte[headers.length-2];
        System.arraycopy(headers, 2, hbuf, 0, headers.length-2);
        byte[] dbuf = new byte[data.length];
        System.arraycopy(data, 0, dbuf, 0, data.length);
        // Decipher header first - functions as IV
        pcfb.blockDecipher(hbuf, 0, hbuf.length);
        pcfb.blockDecipher(dbuf, 0, dbuf.length);
        // Check: Decryption key == hash of data (not including header)
        MessageDigest md256 = SHA256.getMessageDigest();
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
        if((size > 32768) || (size < 0))
            throw new CHKDecodeException("Invalid size: "+size);
        byte[] output = new byte[size];
        // No particular reason to check the padding, is there?
        System.arraycopy(dbuf, 0, output, 0, size);
        return Key.decompress(dontCompress ? false : key.isCompressed(), output, bf, Math.min(maxLength, MAX_LENGTH_BEFORE_COMPRESSION), key.compressionAlgorithm, false);
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
        try {
			Compressed comp = Key.compress(sourceData, dontCompress, alreadyCompressedCodec, sourceLength, MAX_LENGTH_BEFORE_COMPRESSION, CHKBlock.DATA_LENGTH, false);
			finalData = comp.compressedData;
			compressionAlgorithm = comp.compressionAlgorithm;
		} catch (KeyEncodeException e2) {
			throw new CHKEncodeException(e2.getMessage(), e2);
		}
        
        // Now do the actual encode
        
        MessageDigest md256 = SHA256.getMessageDigest();
        // First pad it
        if(finalData.length != 32768) {
            // Hash the data
            if(finalData.length != 0)
            	md256.update(finalData);
            byte[] digest = md256.digest();
            MersenneTwister mt = new MersenneTwister(digest);
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
        header[0] = (byte)(KeyBlock.HASH_SHA256 >> 8);
        header[1] = (byte)(KeyBlock.HASH_SHA256 & 0xff);
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
        md256.update(header);
        byte[] finalHash = md256.digest(data);
        
        // Now convert it into a ClientCHK
        key = new ClientCHK(finalHash, encKey, asMetadata, Key.ALGO_AES_PCFB_256_SHA256, compressionAlgorithm);
        
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

    public ClientKey getClientKey() {
        return key;
    }

	public boolean isMetadata() {
		return key.isMetadata();
	}

}
