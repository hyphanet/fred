/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import freenet.crypt.BlockCipher;
import freenet.crypt.CTRBlockCipher;
import freenet.crypt.JceLoader;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.Util;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.Key.Compressed;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.math.MersenneTwister;

/**
 * @author amphibian
 * 
 * Client CHKBlock - provides functions for decoding, holds a client-key.
 */
public class ClientCHKBlock implements ClientKeyBlock {

	final ClientCHK key;
	private final CHKBlock block;
	
    @Override
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
        block = new CHKBlock(data, header, key2.getNodeCHK(), verify, key2.cryptoAlgorithm);
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
	@Override
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
    @Override
    public Bucket decode(BucketFactory bf, int maxLength, boolean dontCompress) throws CHKDecodeException, IOException {
    	return decode(bf, maxLength, dontCompress, false);
    }
    
    // forceNoJCA for unit tests.
    Bucket decode(BucketFactory bf, int maxLength, boolean dontCompress, boolean forceNoJCA) throws CHKDecodeException, IOException {
    	if(key.cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256)
    		return decodeOld(bf, maxLength, dontCompress);
    	else if(key.cryptoAlgorithm == Key.ALGO_AES_CTR_256_SHA256)
		{
				if(Rijndael.AesCtrProvider == null || forceNoJCA)
					return decodeNewNoJCA(bf, maxLength, dontCompress);
				else
					return decodeNew(bf, maxLength, dontCompress);
		}
		else
    		throw new UnsupportedOperationException();
    }

	
    /**
     * Decode the CHK and recover the original data
     * @return the original data
     * @throws IOException If there is a bucket error.
     */
    @SuppressWarnings("deprecation") // FIXME Back compatibility, using dubious ciphers; remove eventually.
    public Bucket decodeOld(BucketFactory bf, int maxLength, boolean dontCompress) throws CHKDecodeException, IOException {
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
        PCFBMode pcfb = PCFBMode.create(cipher);
        byte[] headers = block.headers;
        byte[] data = block.data;
		byte[] hbuf = Arrays.copyOfRange(headers, 2, headers.length);
        byte[] dbuf = Arrays.copyOf(data, data.length);
        // Decipher header first - functions as IV
        pcfb.blockDecipher(hbuf, 0, hbuf.length);
        pcfb.blockDecipher(dbuf, 0, dbuf.length);
        MessageDigest md256 = SHA256.getMessageDigest();
        byte[] dkey = key.cryptoKey;
        // Check: IV == hash of decryption key
        byte[] predIV = md256.digest(dkey);
        SHA256.returnMessageDigest(md256); md256 = null;
        // Extract the IV
        byte[] iv = Arrays.copyOf(hbuf, 32);
        if(!Arrays.equals(iv, predIV))
            throw new CHKDecodeException("Check failed: Decrypted IV == H(decryption key)");
        // Checks complete
        int size = ((hbuf[32] & 0xff) << 8) + (hbuf[33] & 0xff);
        if((size > 32768) || (size < 0)) {
            throw new CHKDecodeException("Invalid size: "+size);
        }
        return Key.decompress(dontCompress ? false : key.isCompressed(), dbuf, size, bf, 
        		Math.min(maxLength, CHKBlock.MAX_LENGTH_BEFORE_COMPRESSION), key.compressionAlgorithm, false);
    }
    
	private static final Provider hmacProvider;
	static private long benchmark(Mac hmac) throws GeneralSecurityException
	{
		long times = Long.MAX_VALUE;
		byte[] input = new byte[1024];
		byte[] output = new byte[hmac.getMacLength()];
		byte[] key = new byte[Node.SYMMETRIC_KEY_LENGTH];
		final String algo = hmac.getAlgorithm();
		hmac.init(new SecretKeySpec(key, algo));
		// warm-up
		for (int i = 0; i < 32; i++) {
			hmac.update(input, 0, input.length);
			hmac.doFinal(output, 0);
			System.arraycopy(output, 0, input, (i*output.length)%(input.length-output.length), output.length);
		}
		System.arraycopy(output, 0, key, 0, Math.min(key.length, output.length));
		for (int i = 0; i < 1024; i++) {
			long startTime = System.nanoTime();
			hmac.init(new SecretKeySpec(key, algo));
			for (int j = 0; j < 8; j++) {
				for (int k = 0; k < 32; k ++) {
					hmac.update(input, 0, input.length);
				}
				hmac.doFinal(output, 0);
			}
			long endTime = System.nanoTime();
			times = Math.min(endTime - startTime, times);
			System.arraycopy(output, 0, input, 0, output.length);
			System.arraycopy(output, 0, key, 0, Math.min(key.length, output.length));
		}
		return times;
	}
	static {
		try {
			final Class<ClientCHKBlock> clazz = ClientCHKBlock.class;
			final String algo = "HmacSHA256";
			final Provider sun = JceLoader.SunJCE;
			SecretKeySpec dummyKey = new SecretKeySpec(new byte[Node.SYMMETRIC_KEY_LENGTH], algo);
			Mac hmac = Mac.getInstance(algo);
			hmac.init(dummyKey); // resolve provider
			boolean logMINOR = Logger.shouldLog(Logger.LogLevel.MINOR, clazz);
			if (sun != null) {
				// SunJCE provider is faster (in some configurations)
				try {
					Mac sun_hmac = Mac.getInstance(algo, sun);
					sun_hmac.init(dummyKey); // resolve provider
					if (hmac.getProvider() != sun_hmac.getProvider()) {
						long time_def = benchmark(hmac);
						long time_sun = benchmark(sun_hmac);
						System.out.println(algo + " (" + hmac.getProvider() + "): " + time_def + "ns");
						System.out.println(algo + " (" + sun_hmac.getProvider() + "): " + time_sun + "ns");
						if(logMINOR) {
							Logger.minor(clazz, algo + "/" + hmac.getProvider() + ": " + time_def + "ns");
							Logger.minor(clazz, algo + "/" + sun_hmac.getProvider() + ": " + time_sun + "ns");
						}
						if (time_sun < time_def) {
							hmac = sun_hmac;
						}
					}
				} catch(GeneralSecurityException e) {
					Logger.warning(clazz, algo + "@" + sun + " benchmark failed", e);
					// ignore

				} catch(Throwable e) {
					Logger.error(clazz, algo + "@" + sun + " benchmark failed", e);
					// ignore
				}
			}
			hmacProvider = hmac.getProvider();
			System.out.println(algo + ": using " + hmacProvider);
			Logger.normal(clazz, algo + ": using " + hmacProvider);
		} catch(GeneralSecurityException e) {
			// impossible 
			throw new Error(e);
		}
	}

    /**
     * Decode the CHK and recover the original data
     * @return the original data
     * @throws IOException If there is a bucket error.
     */
    public Bucket decodeNew(BucketFactory bf, int maxLength, boolean dontCompress) throws CHKDecodeException, IOException {
		if(key.cryptoAlgorithm != Key.ALGO_AES_CTR_256_SHA256)
			throw new UnsupportedOperationException();
        byte[] headers = block.headers;
        byte[] data = block.data;
    	byte[] hash = Arrays.copyOfRange(headers, 2, 2+32);
        byte[] cryptoKey = key.cryptoKey;
        if(cryptoKey.length < Node.SYMMETRIC_KEY_LENGTH)
            throw new CHKDecodeException("Crypto key too short");
		try {
        Cipher cipher = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cryptoKey, "AES"), new IvParameterSpec(hash, 0, 16));
        byte[] plaintext = new byte[data.length + 2];
		int moved = cipher.update(data, 0, data.length, plaintext);
		cipher.doFinal(headers, hash.length+2, 2, plaintext, moved);
        int size = ((plaintext[data.length] & 0xff) << 8) + (plaintext[data.length + 1] & 0xff);
        if((size > 32768) || (size < 0)) {
            throw new CHKDecodeException("Invalid size: "+size);
        }
        // Check the hash.
        Mac hmac = Mac.getInstance("HmacSHA256", hmacProvider);
        hmac.init(new SecretKeySpec(cryptoKey, "HmacSHA256"));
        hmac.update(plaintext); // plaintext includes lengthBytes
        byte[] hashCheck = hmac.doFinal();
        if(!Arrays.equals(hash, hashCheck)) {
        	throw new CHKDecodeException("HMAC is wrong, wrong decryption key?");
        }
        return Key.decompress(dontCompress ? false : key.isCompressed(), plaintext, size, bf, 
        		Math.min(maxLength, CHKBlock.MAX_LENGTH_BEFORE_COMPRESSION), key.compressionAlgorithm, false);
		} catch(GeneralSecurityException e) {
			throw new CHKDecodeException("Problem with JCA, should be impossible!", e);
		}
    }

    /**
     * Decode using Freenet's built in crypto. FIXME remove once Java 1.7
     * is mandatory. Note that we assume that HMAC SHA256 is available; the
     * problem is AES is limited to 128 bits.
     * @return the original data
     * @throws IOException If there is a bucket error.
     */
    public Bucket decodeNewNoJCA(BucketFactory bf, int maxLength, boolean dontCompress) throws CHKDecodeException, IOException {
		if(key.cryptoAlgorithm != Key.ALGO_AES_CTR_256_SHA256)
			throw new UnsupportedOperationException();
        byte[] headers = block.headers;
        byte[] data = block.data;
    	byte[] hash = Arrays.copyOfRange(headers, 2, 2+32);
        byte[] cryptoKey = key.cryptoKey;
        if(cryptoKey.length < Node.SYMMETRIC_KEY_LENGTH)
            throw new CHKDecodeException("Crypto key too short");
        Rijndael aes;
        try {
			aes = new Rijndael(256, 128);
		} catch (UnsupportedCipherException e) {
			// Impossible.
			throw new Error(e);
		}
		aes.initialize(cryptoKey);
        CTRBlockCipher cipher = new CTRBlockCipher(aes);
        cipher.init(hash, 0, 16);
        byte[] plaintext = new byte[data.length];
        cipher.processBytes(data, 0, data.length, plaintext, 0);
        byte[] lengthBytes = new byte[2];
        cipher.processBytes(headers, hash.length+2, 2, lengthBytes, 0);
        int size = ((lengthBytes[0] & 0xff) << 8) + (lengthBytes[1] & 0xff);
        if((size > 32768) || (size < 0)) {
            throw new CHKDecodeException("Invalid size: "+size);
        }
		try {
        // Check the hash.
        Mac hmac = Mac.getInstance("HmacSHA256", hmacProvider);
        hmac.init(new SecretKeySpec(cryptoKey, "HmacSHA256"));
        hmac.update(plaintext);
        hmac.update(lengthBytes);
        byte[] hashCheck = hmac.doFinal();
        if(!Arrays.equals(hash, hashCheck)) {
        	throw new CHKDecodeException("HMAC is wrong, wrong decryption key?");
        }
		} catch(GeneralSecurityException e) {
			throw new CHKDecodeException("Problem with JCA, should be impossible!", e);
		}
        return Key.decompress(dontCompress ? false : key.isCompressed(), plaintext, size, bf, 
        		Math.min(maxLength, CHKBlock.MAX_LENGTH_BEFORE_COMPRESSION), key.compressionAlgorithm, false);
    }

    /**
     * Encode a splitfile block.
     * @param data The data to encode. Must be exactly DATA_LENGTH bytes.
     * @param cryptoKey The encryption key. Can be null in which case this is equivalent to a normal block
     * encode.
     */
    static public ClientCHKBlock encodeSplitfileBlock(byte[] data, byte[] cryptoKey, byte cryptoAlgorithm) throws CHKEncodeException {
    	if(data.length != CHKBlock.DATA_LENGTH) throw new IllegalArgumentException();
    	if(cryptoKey != null && cryptoKey.length != 32) throw new IllegalArgumentException();
        MessageDigest md256 = SHA256.getMessageDigest();
        // No need to pad
        if(cryptoKey == null) {
        	cryptoKey = md256.digest(data);
        }
        	if(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256)
        		return innerEncode(data, CHKBlock.DATA_LENGTH, md256, cryptoKey, false, (short)-1, cryptoAlgorithm);
        	else if(cryptoAlgorithm != Key.ALGO_AES_CTR_256_SHA256)
        		throw new IllegalArgumentException("Unknown crypto algorithm: "+cryptoAlgorithm);
        	if(Rijndael.AesCtrProvider == null) {
        		return encodeNewNoJCA(data, CHKBlock.DATA_LENGTH, md256, cryptoKey, false, (short)-1, cryptoAlgorithm, KeyBlock.HASH_SHA256);
        	} else {
        		return encodeNew(data, CHKBlock.DATA_LENGTH, md256, cryptoKey, false, (short)-1, cryptoAlgorithm, KeyBlock.HASH_SHA256);
			}
    }
    
    /**
     * Encode a Bucket of data to a CHKBlock.
     * @param sourceData The bucket of data to encode. Can be arbitrarily large.
     * @param asMetadata Is this a metadata key?
     * @param dontCompress If set, don't even try to compress.
     * @param alreadyCompressedCodec If !dontCompress, and this is >=0, then the
     * data is already compressed, and this is the algorithm.
     * @param compressorDescriptor 
     * @param cryptoAlgorithm 
     * @param cryptoKey 
     * @throws CHKEncodeException
     * @throws IOException If there is an error reading from the Bucket.
     * @throws InvalidCompressionCodecException 
     */
    static public ClientCHKBlock encode(
				Bucket sourceData,
				boolean asMetadata,
				boolean dontCompress,
				short alreadyCompressedCodec,
				long sourceLength,
				String compressorDescriptor,
				byte[] cryptoKey,
				byte cryptoAlgorithm) throws CHKEncodeException, IOException {
    	return encode(sourceData, asMetadata, dontCompress, alreadyCompressedCodec, sourceLength, compressorDescriptor,
					cryptoKey, cryptoAlgorithm, false);
    }

    // forceNoJCA for unit tests.
    static ClientCHKBlock encode(
				Bucket sourceData,
				boolean asMetadata,
				boolean dontCompress,
				short alreadyCompressedCodec,
				long sourceLength,
				String compressorDescriptor,
				byte[] cryptoKey,
				byte cryptoAlgorithm,
				boolean forceNoJCA) throws CHKEncodeException, IOException {
        byte[] finalData = null;
        byte[] data;
        short compressionAlgorithm = -1;
        try {
			Compressed comp = Key.compress(sourceData, dontCompress, alreadyCompressedCodec, sourceLength, CHKBlock.MAX_LENGTH_BEFORE_COMPRESSION, CHKBlock.DATA_LENGTH, false, compressorDescriptor);
			finalData = comp.compressedData;
			compressionAlgorithm = comp.compressionAlgorithm;
		} catch (KeyEncodeException e2) {
			throw new CHKEncodeException(e2.getMessage(), e2);
		} catch (InvalidCompressionCodecException e2) {
			throw new CHKEncodeException(e2.getMessage(), e2);
		}
        // Now do the actual encode
        
        MessageDigest md256 = SHA256.getMessageDigest();
        // First pad it
        int dataLength = finalData.length;
        if(finalData.length != 32768) {
            // Hash the data
            if(finalData.length != 0)
            	md256.update(finalData);
            byte[] digest = md256.digest();
            MersenneTwister mt = new MersenneTwister(digest);
			data = Arrays.copyOf(finalData, 32768);
			Util.randomBytes(mt, data, finalData.length, 32768-finalData.length);
        } else {
        	data = finalData;
        }
        // Now make the header
        byte[] encKey;
        if(cryptoKey != null)
        	encKey = cryptoKey;
        else
        	encKey = md256.digest(data);
    	if(cryptoAlgorithm == 0) {
    		// TODO find all such cases and fix them.
    		Logger.error(ClientCHKBlock.class, "Passed in 0 crypto algorithm", new Exception("warning"));
    		cryptoAlgorithm = Key.ALGO_AES_PCFB_256_SHA256;
    	}
        if(cryptoAlgorithm == Key.ALGO_AES_PCFB_256_SHA256)
        	return innerEncode(data, dataLength, md256, encKey, asMetadata, compressionAlgorithm, cryptoAlgorithm);
		else {
				if(Rijndael.AesCtrProvider == null || forceNoJCA)
					return encodeNewNoJCA(data, dataLength, md256, encKey, asMetadata, compressionAlgorithm, cryptoAlgorithm, KeyBlock.HASH_SHA256);
				else
					return encodeNew(data, dataLength, md256, encKey, asMetadata, compressionAlgorithm, cryptoAlgorithm, KeyBlock.HASH_SHA256);
		}
    }
    
    /**
     * Format:
     * [0-1]: Block hash algorithm
     * [2-34]: HMAC (with cryptokey) of data + length bytes.
     * [35-36]: Length bytes.
     * Encryption: CTR with IV = 1st 16 bytes of the hash. (It has to be 
     * deterministic as this is a CHK and we need to be able to reinsert them
     * easily):
     * - Data
     * - Length bytes.
     * @param data Data should already have been padded.
     * @param dataLength Length of original data. Between 0 and 32768.
     * @param md256 Convenient reuse of hash object.
     * @param encKey Encryption key for the data, part of the URI.
     * @param asMetadata Whether the final CHK is metadata or not.
     * @param compressionAlgorithm The compression algorithm used.
     * @param cryptoAlgorithm The encryption algorithm used.
     * @return
     */
    public static ClientCHKBlock encodeNew(byte[] data, int dataLength, MessageDigest md256, byte[] encKey, boolean asMetadata, short compressionAlgorithm, byte cryptoAlgorithm, int blockHashAlgorithm) throws CHKEncodeException {
    	if(cryptoAlgorithm != Key.ALGO_AES_CTR_256_SHA256)
    		throw new IllegalArgumentException("Unsupported crypto algorithm "+cryptoAlgorithm);
		try {
    	// IV = HMAC<cryptokey>(plaintext).
        // It's okay that this is the same for 2 blocks with the same key and the same content.
        // In fact that's the point; this is still a Content Hash Key.
        // FIXME And yes we should check on insert for multiple identical keys.
        Mac hmac = Mac.getInstance("HmacSHA256", hmacProvider);
        hmac.init(new SecretKeySpec(encKey, "HmacSHA256"));
        byte[] tmpLen = new byte[] { 
            	(byte)(dataLength >> 8), (byte)(dataLength & 0xff)
            };
        hmac.update(data);
        hmac.update(tmpLen);
        byte[] hash = hmac.doFinal();
        byte[] header = new byte[hash.length+2+2];
    	if(blockHashAlgorithm == 0) cryptoAlgorithm = KeyBlock.HASH_SHA256;
    	if(blockHashAlgorithm != KeyBlock.HASH_SHA256)
    		throw new IllegalArgumentException("Unsupported block hash algorithm "+cryptoAlgorithm);
        header[0] = (byte)(blockHashAlgorithm >> 8);
        header[1] = (byte)(blockHashAlgorithm & 0xff);
        System.arraycopy(hash, 0, header, 2, hash.length);
        SecretKey ckey = new SecretKeySpec(encKey, "AES");
        // CTR mode IV is only 16 bytes.
        // That's still plenty though. It will still be unique.
        Cipher cipher = Cipher.getInstance("AES/CTR/NOPADDING", Rijndael.AesCtrProvider);
        cipher.init(Cipher.ENCRYPT_MODE, ckey, new IvParameterSpec(hash, 0, 16));
        byte[] cdata = new byte[data.length];
		int moved = cipher.update(data, 0, data.length, cdata);
		if (moved == data.length) {
			cipher.doFinal(tmpLen, 0, 2, header, hash.length+2);
		} else {
			// FIXME inefficient
			byte[] tmp = cipher.doFinal(tmpLen, 0, 2);
			System.arraycopy(tmp, 0, cdata, moved, tmp.length-2);
			System.arraycopy(tmp, tmp.length-2,	header, hash.length+2, 2);
		}
        
        // Now calculate the final hash
        md256.update(header);
        byte[] finalHash = md256.digest(cdata);
        
        SHA256.returnMessageDigest(md256);
        
        // Now convert it into a ClientCHK
        ClientCHK finalKey = new ClientCHK(finalHash, encKey, asMetadata, cryptoAlgorithm, compressionAlgorithm);
        
        try {
        	return new ClientCHKBlock(cdata, header, finalKey, false);
        } catch (CHKVerifyException e3) {
            //WTF?
            throw new Error(e3);
        }
		} catch (GeneralSecurityException e) {
			throw new CHKEncodeException("Problem with JCA, should be impossible!", e);
		}
    }
    
    /**
     * Encode using Freenet's built in crypto. FIXME remove once Java 1.7
     * is mandatory. Note that we assume that HMAC SHA256 is available; the
     * problem is AES is limited to 128 bits.
     * @param data Data should already have been padded.
     * @param dataLength Length of original data. Between 0 and 32768.
     * @param md256 Convenient reuse of hash object.
     * @param encKey Encryption key for the data, part of the URI.
     * @param asMetadata Whether the final CHK is metadata or not.
     * @param compressionAlgorithm The compression algorithm used.
     * @param cryptoAlgorithm The encryption algorithm used.
     * @return
     * @throws CHKVerifyException
     * @throws CHKEncodeException
     */
    public static ClientCHKBlock encodeNewNoJCA(byte[] data, int dataLength, MessageDigest md256, byte[] encKey, boolean asMetadata, short compressionAlgorithm, byte cryptoAlgorithm, int blockHashAlgorithm) throws CHKEncodeException {
    	if(cryptoAlgorithm != Key.ALGO_AES_CTR_256_SHA256)
    		throw new IllegalArgumentException("Unsupported crypto algorithm "+cryptoAlgorithm);
		try {
    	// IV = HMAC<cryptokey>(plaintext).
        // It's okay that this is the same for 2 blocks with the same key and the same content.
        // In fact that's the point; this is still a Content Hash Key.
        // FIXME And yes we should check on insert for multiple identical keys.
        Mac hmac = Mac.getInstance("HmacSHA256", hmacProvider);
        hmac.init(new SecretKeySpec(encKey, "HmacSHA256"));
        byte[] tmpLen = new byte[] { 
            	(byte)(dataLength >> 8), (byte)(dataLength & 0xff)
            };
        hmac.update(data);
        hmac.update(tmpLen);
        byte[] hash = hmac.doFinal();
        byte[] header = new byte[hash.length+2+2];
    	if(blockHashAlgorithm == 0) cryptoAlgorithm = KeyBlock.HASH_SHA256;
    	if(blockHashAlgorithm != KeyBlock.HASH_SHA256)
    		throw new IllegalArgumentException("Unsupported block hash algorithm "+cryptoAlgorithm);
        header[0] = (byte)(blockHashAlgorithm >> 8);
        header[1] = (byte)(blockHashAlgorithm & 0xff);
        Rijndael aes;
		try {
			aes = new Rijndael(256, 128);
		} catch (UnsupportedCipherException e) {
			// Impossible
			throw new Error(e);
		}
        aes.initialize(encKey);
        CTRBlockCipher ctr = new CTRBlockCipher(aes);
        // CTR mode IV is only 16 bytes.
        // That's still plenty though. It will still be unique.
        ctr.init(hash, 0, 16);
        System.arraycopy(hash, 0, header, 2, hash.length);
        byte[] cdata = new byte[data.length];
        ctr.processBytes(data, 0, data.length, cdata, 0);
        ctr.processBytes(tmpLen, 0, 2, header, hash.length+2);
        
        // Now calculate the final hash
        md256.update(header);
        byte[] finalHash = md256.digest(cdata);
        
        SHA256.returnMessageDigest(md256);
        
        // Now convert it into a ClientCHK
        ClientCHK finalKey = new ClientCHK(finalHash, encKey, asMetadata, cryptoAlgorithm, compressionAlgorithm);
        
        try {
        	return new ClientCHKBlock(cdata, header, finalKey, false);
        } catch (CHKVerifyException e3) {
            //WTF?
            throw new Error(e3);
        }
		} catch (GeneralSecurityException e) {
			throw new CHKEncodeException("Problem with JCA, should be impossible!", e);
		}
    }
    
    @SuppressWarnings("deprecation") // FIXME Back compatibility, using dubious ciphers; remove eventually.
    public static ClientCHKBlock innerEncode(byte[] data, int dataLength, MessageDigest md256, byte[] encKey, boolean asMetadata, short compressionAlgorithm, byte cryptoAlgorithm) {
        data = data.clone(); // Will overwrite otherwise. Callers expect data not to be clobbered.
    	if(cryptoAlgorithm != Key.ALGO_AES_PCFB_256_SHA256)
    		throw new IllegalArgumentException("Unsupported crypto algorithm "+cryptoAlgorithm);
        byte[] header;
        ClientCHK key;
        // IV = E(H(crypto key))
        byte[] plainIV = md256.digest(encKey);
        header = new byte[plainIV.length+2+2];
        header[0] = (byte)(KeyBlock.HASH_SHA256 >> 8);
        header[1] = (byte)(KeyBlock.HASH_SHA256 & 0xff);
        System.arraycopy(plainIV, 0, header, 2, plainIV.length);
        header[plainIV.length+2] = (byte)(dataLength >> 8);
        header[plainIV.length+3] = (byte)(dataLength & 0xff);
        // GRRR, java 1.4 does not have any symmetric crypto
        // despite exposing asymmetric and hashes!
        
        // Now encrypt the header, then the data, using the same PCFB instance
        BlockCipher cipher;
        try {
            cipher = new Rijndael(256, 256);
        } catch (UnsupportedCipherException e) {
        	Logger.error(ClientCHKBlock.class, "Impossible: "+e, e);
            throw new Error(e);
        }
        cipher.initialize(encKey);
        
        // FIXME CRYPTO plainIV, the hash of the crypto key, is encrypted with a null IV.
        // In other words, it is XORed with E(0).
        // For splitfiles we reuse the same decryption key for multiple blocks; it is derived from the overall hash,
        // or it is set randomly.
        // So the plaintext *and* ciphertext IV is always the same.
        // And the following 32 bytes are always XORed with the same value.
        // Ouch!
        // Those bytes being 2 bytes for the length, followed by the first 30 bytes of the data.
        
        PCFBMode pcfb = PCFBMode.create(cipher);
        pcfb.blockEncipher(header, 2, header.length-2);
        pcfb.blockEncipher(data, 0, data.length);
        
        // Now calculate the final hash
        md256.update(header);
        byte[] finalHash = md256.digest(data);
        
        SHA256.returnMessageDigest(md256);
        
        // Now convert it into a ClientCHK
        key = new ClientCHK(finalHash, encKey, asMetadata, cryptoAlgorithm, compressionAlgorithm);
        
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
     * @param compressorDescriptor Should be null, or list of compressors to try.
     * @throws InvalidCompressionCodecException
     */
    static public ClientCHKBlock encode(
				byte[] sourceData,
				boolean asMetadata,
				boolean dontCompress,
				short alreadyCompressedCodec,
				int sourceLength,
				String compressorDescriptor) throws CHKEncodeException, InvalidCompressionCodecException {
    	try {
			return encode(new ArrayBucket(sourceData), asMetadata, dontCompress, alreadyCompressedCodec, sourceLength, compressorDescriptor,
					null, Key.ALGO_AES_CTR_256_SHA256);
		} catch (IOException e) {
			// Can't happen
			throw new Error(e);
		}
    }

    @Override
    public ClientCHK getClientKey() {
        return key;
    }

	@Override
	public boolean isMetadata() {
		return key.isMetadata();
	}

	@Override
	public int hashCode() {
		return key.hashCode;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof ClientCHKBlock)) return false;
		ClientCHKBlock block = (ClientCHKBlock) o;
		if(!key.equals(block.key)) return false;
		return block.block.equals(this.block);
	}

	@Override
	public CHKBlock getBlock() {
		return block;
	}

	@Override
	public Key getKey() {
		return block.getKey();
	}
}
