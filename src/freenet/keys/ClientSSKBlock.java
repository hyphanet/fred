/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.IOException;
import java.util.Arrays;

import freenet.crypt.PCFBMode;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;

public class ClientSSKBlock extends SSKBlock implements ClientKeyBlock {
	
	static final int DATA_DECRYPT_KEY_LENGTH = 32;
	
	static public final int MAX_DECOMPRESSED_DATA_LENGTH = 32768;
	
	/** Is metadata. Set on decode. */
	private boolean isMetadata;
	/** Has decoded? */
	private boolean decoded;
	/** Client-key. This contains the decryption key etc. */
	private final ClientSSK key;

	/** Compression algorithm from last time tried to decompress. */
	private short compressionAlgorithm = -1;
	
	public ClientSSKBlock(byte[] data, byte[] headers, ClientSSK key, boolean dontVerify) throws SSKVerifyException {
		super(data, headers, (NodeSSK) key.getNodeKey(true), dontVerify);
		this.key = key;
	}
	
	public static ClientSSKBlock construct(SSKBlock block, ClientSSK key) throws SSKVerifyException {
		// Constructor expects clientkey to have the pubkey.
		// In the case of binary blobs, the block may have it instead.
		if(key.getPubKey() == null)
			key.setPublicKey(block.getPubKey());
		return new ClientSSKBlock(block.data, block.headers, key, false);
	}
	
	/**
	 * Decode the data.
	 */
	@Override
	public Bucket decode(BucketFactory factory, int maxLength, boolean dontDecompress) throws KeyDecodeException, IOException {
		/* We know the signature is valid because it is checked in the constructor. */
		/* We also know e(h(docname)) is valid */
		byte[] decryptedHeaders = new byte[ENCRYPTED_HEADERS_LENGTH];
		System.arraycopy(headers, headersOffset, decryptedHeaders, 0, ENCRYPTED_HEADERS_LENGTH);
		Rijndael aes;
		try {
			Logger.minor(this, "cryptoAlgorithm="+key.cryptoAlgorithm+" for "+getClientKey().getURI());
			aes = new Rijndael(256,256);
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		aes.initialize(key.cryptoKey);
		// ECB-encrypted E(H(docname)) serves as IV.
		PCFBMode pcfb = PCFBMode.create(aes, key.ehDocname);
		pcfb.blockDecipher(decryptedHeaders, 0, decryptedHeaders.length);
		// First 32 bytes are the key
		byte[] dataDecryptKey = Arrays.copyOf(decryptedHeaders, DATA_DECRYPT_KEY_LENGTH);
		aes.initialize(dataDecryptKey);
		byte[] dataOutput = data.clone();
		// Data decrypt key should be unique, so use it as IV
		pcfb.reset(dataDecryptKey);
		pcfb.blockDecipher(dataOutput, 0, dataOutput.length);
		// 2 bytes - data length
		int dataLength = ((decryptedHeaders[DATA_DECRYPT_KEY_LENGTH] & 0xff) << 8) +
			(decryptedHeaders[DATA_DECRYPT_KEY_LENGTH+1] & 0xff);
		// Metadata flag is top bit
		if((dataLength & 32768) != 0) {
			dataLength = dataLength & ~32768;
			isMetadata = true;
		}
		if(dataLength > data.length) {
			throw new SSKDecodeException("Data length: "+dataLength+" but data.length="+data.length);
		}
		
        compressionAlgorithm = (short)(((decryptedHeaders[DATA_DECRYPT_KEY_LENGTH+2] & 0xff) << 8) + (decryptedHeaders[DATA_DECRYPT_KEY_LENGTH+3] & 0xff));
        decoded = true;
        
        if(dontDecompress) {
        	if(compressionAlgorithm == (short)-1)
        		return BucketTools.makeImmutableBucket(factory, dataOutput, dataLength);
        	else if(dataLength < 2)
        		throw new SSKDecodeException("Data length is less than 2 yet compressed!");
        	else
        		return BucketTools.makeImmutableBucket(factory, dataOutput, 2, dataLength - 2);
        }

        Bucket b = Key.decompress(compressionAlgorithm >= 0, dataOutput, dataLength, factory, Math.min(MAX_DECOMPRESSED_DATA_LENGTH, maxLength), compressionAlgorithm, true);
        return b;
	}

	@Override
	public boolean isMetadata() {
		if(!decoded)
			throw new IllegalStateException("Cannot read isMetadata before decoded");
		return isMetadata;
	}

	@Override
	public ClientSSK getClientKey() {
		return key;
	}

	public short getCompressionCodec() {
		return compressionAlgorithm;
	}
	
	@Override
	public byte[] memoryDecode() throws KeyDecodeException {
		return memoryDecode(false);
	}
	
    /**
     * Decode into RAM, if short.
     * @throws KeyDecodeException 
     */
	public byte[] memoryDecode(boolean dontDecompress) throws KeyDecodeException {
		try {
			ArrayBucket a = (ArrayBucket) decode(new ArrayBucketFactory(), 32*1024, dontDecompress);
			return BucketTools.toByteArray(a); // FIXME
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	@Override
	public int hashCode() {
		return super.hashCode() ^ key.hashCode();
	}
	
	@Override
	public boolean equals(Object o) {
		if(!(o instanceof ClientSSKBlock)) return false;
		ClientSSKBlock block = (ClientSSKBlock) o;
		if(!key.equals(block.key)) return false;
		return super.equals(o);
	}
	
}
