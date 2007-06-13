/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import freenet.crypt.CryptFormatException;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.io.WritableToDataOutputStream;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;

/**
 * @author amphibian
 * 
 * Base class for node keys.
 */
public abstract class Key implements WritableToDataOutputStream {

    final int hash;
    double cachedNormalizedDouble;
    /** Whatever its type, it will need a routingKey ! */
    final byte[] routingKey;
    
    /** Code for 256-bit AES with PCFB and SHA-256 */
    static final byte ALGO_AES_PCFB_256_SHA256 = 2;
    /** Code for old, insecure (only encrypts first 128 bits of block) 256-bit AES with PCFB and SHA-256.
     * FIXME: REMOVE!! */
	static final byte ALGO_INSECURE_AES_PCFB_256_SHA256 = 1;
	
	public static boolean ALLOW_INSECURE_CLIENT_CHKS;
	public static boolean ALLOW_INSECURE_CLIENT_SSKS;

    protected Key(byte[] routingKey) {
    	this.routingKey = routingKey;
    	hash = Fields.hashCode(routingKey);
        cachedNormalizedDouble = -1;
    }
    
    /**
     * Write to disk.
     * Take up exactly 22 bytes.
     * @param _index
     */
    public abstract void write(DataOutput _index) throws IOException;

    /**
     * Read a Key from a RandomAccessFile
     * @param raf The file to read from.
     * @return a Key, or throw an exception, or return null if the key is not parsable.
     */
    public static final Key read(DataInput raf) throws IOException {
    	byte type = raf.readByte();
    	byte subtype = raf.readByte();
        if(type == NodeCHK.BASE_TYPE) {
            return NodeCHK.readCHK(raf, subtype);
        } else if(type == NodeSSK.BASE_TYPE)
        	return NodeSSK.readSSK(raf, subtype);
        
        throw new IOException("Unrecognized format: "+type);
    }
    
	public static KeyBlock createBlock(short keyType, byte[] keyBytes, byte[] headersBytes, byte[] dataBytes, byte[] pubkeyBytes) throws KeyVerifyException {
		byte type = (byte)(keyType >> 8);
		byte subtype = (byte)(keyType & 0xFF);
		if(type == NodeCHK.BASE_TYPE) {
			return CHKBlock.construct(dataBytes, headersBytes);
		} else if(type == NodeSSK.BASE_TYPE) {
			DSAPublicKey pubKey;
			try {
				pubKey = new DSAPublicKey(pubkeyBytes);
			} catch (IOException e) {
				throw new KeyVerifyException("Failed to construct pubkey: "+e, e);
			} catch (CryptFormatException e) {
				throw new KeyVerifyException("Failed to construct pubkey: "+e, e);
			}
			NodeSSK key = new NodeSSK(pubKey.asBytesHash(), keyBytes, pubKey, subtype);
			return new SSKBlock(dataBytes, headersBytes, key, false);
		} else {
			throw new KeyVerifyException("No such key type "+Integer.toHexString(type));
		}
	}

    /**
     * Convert the key to a double between 0.0 and 1.0.
     * Normally we will hash the key first, in order to
     * make chosen-key attacks harder.
     */
    public synchronized double toNormalizedDouble() {
        if(cachedNormalizedDouble > 0) return cachedNormalizedDouble;
        MessageDigest md = SHA256.getMessageDigest();
        md.update(routingKey);
        int TYPE = getType();
        md.update((byte)(TYPE >> 8));
        md.update((byte)TYPE);
        byte[] digest = md.digest();
        SHA256.returnMessageDigest(md); md = null;
        long asLong = Math.abs(Fields.bytesToLong(digest));
        // Math.abs can actually return negative...
        if(asLong == Long.MIN_VALUE)
            asLong = Long.MAX_VALUE;
        cachedNormalizedDouble = ((double)asLong)/((double)Long.MAX_VALUE);
        return cachedNormalizedDouble;
    }
    
    public abstract short getType();
    
    public int hashCode() {
        return hash;
    }
    
    public boolean equals(Object o){
    	if(o == null || !(o instanceof Key)) return false;
    	return Arrays.equals(routingKey, ((Key)o).routingKey);
    }
    
    static Bucket decompress(boolean isCompressed, byte[] output, int outputLength, BucketFactory bf, int maxLength, short compressionAlgorithm, boolean shortLength) throws CHKDecodeException, IOException {
        if(isCompressed) {
        	if(Logger.shouldLog(Logger.MINOR, Key.class))
        		Logger.minor(Key.class, "Decompressing "+output.length+" bytes in decode with codec "+compressionAlgorithm);
            if(output.length < (shortLength ? 3 : 5)) throw new CHKDecodeException("No bytes to decompress");
            // Decompress
            // First get the length
            int len;
            if(shortLength)
            	len = ((output[0] & 0xff) << 8) + (output[1] & 0xff);
            else 
            	len = ((((((output[0] & 0xff) << 8) + (output[1] & 0xff)) << 8) + (output[2] & 0xff)) << 8) +
            		(output[3] & 0xff);
            if(len > maxLength)
                throw new TooBigException("Invalid precompressed size: "+len);
            Compressor decompressor = Compressor.getCompressionAlgorithmByMetadataID(compressionAlgorithm);
            Bucket inputBucket = new SimpleReadOnlyArrayBucket(output, shortLength?2:4, outputLength-(shortLength?2:4));
            try {
				return decompressor.decompress(inputBucket, bf, maxLength, -1, null);
			} catch (CompressionOutputSizeException e) {
				throw new TooBigException("Too big");
			}
        } else {
        	return BucketTools.makeImmutableBucket(bf, output, outputLength);
        }
	}

    static class Compressed {
    	public Compressed(byte[] finalData, short compressionAlgorithm2) {
    		this.compressedData = finalData;
    		this.compressionAlgorithm = compressionAlgorithm2;
		}
		byte[] compressedData;
    	short compressionAlgorithm;
    }
    
    static Compressed compress(Bucket sourceData, boolean dontCompress, short alreadyCompressedCodec, long sourceLength, long MAX_LENGTH_BEFORE_COMPRESSION, int MAX_COMPRESSED_DATA_LENGTH, boolean shortLength) throws KeyEncodeException, IOException {
    	byte[] finalData = null;
        short compressionAlgorithm = -1;
        int maxCompressedDataLength = MAX_COMPRESSED_DATA_LENGTH;
        if(shortLength)
        	maxCompressedDataLength -= 2;
        else
        	maxCompressedDataLength -= 4;
        if(sourceData.size() > MAX_LENGTH_BEFORE_COMPRESSION)
            throw new KeyEncodeException("Too big");
        if((!dontCompress) || (alreadyCompressedCodec >= 0)) {
        	byte[] cbuf = null;
        	if(alreadyCompressedCodec >= 0) {
           		if(sourceData.size() > maxCompressedDataLength)
        			throw new TooBigException("Too big (precompressed)");
        		compressionAlgorithm = alreadyCompressedCodec;
        		cbuf = BucketTools.toByteArray(sourceData);
        		if(sourceLength > MAX_LENGTH_BEFORE_COMPRESSION)
        			throw new TooBigException("Too big");
        	} else {
        		if (sourceData.size() > maxCompressedDataLength) {
					// Determine the best algorithm
					for (int i = 0; i < Compressor.countCompressAlgorithms(); i++) {
						Compressor comp = Compressor
								.getCompressionAlgorithmByDifficulty(i);
						ArrayBucket compressedData;
						try {
							compressedData = (ArrayBucket) comp.compress(
									sourceData, new ArrayBucketFactory(), maxCompressedDataLength);
						} catch (IOException e) {
							throw new Error(e);
						} catch (CompressionOutputSizeException e) {
							continue;
						}
						if (compressedData.size() <= maxCompressedDataLength) {
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
                finalData = new byte[compressedLength+(shortLength?2:4)];
                System.arraycopy(cbuf, 0, finalData, shortLength?2:4, compressedLength);
                if(!shortLength) {
                	finalData[0] = (byte) ((sourceLength >> 24) & 0xff);
                	finalData[1] = (byte) ((sourceLength >> 16) & 0xff);
                	finalData[2] = (byte) ((sourceLength >> 8) & 0xff);
                	finalData[3] = (byte) ((sourceLength) & 0xff);
                } else {
                	finalData[0] = (byte) ((sourceLength >> 8) & 0xff);
                	finalData[1] = (byte) ((sourceLength) & 0xff);
                }
        	}
        }
        if(finalData == null) {
        	// Not compressed or not compressible; no size bytes to be added.
            if(sourceData.size() > MAX_COMPRESSED_DATA_LENGTH) {
                throw new CHKEncodeException("Too big: "+sourceData.size()+" should be "+MAX_COMPRESSED_DATA_LENGTH);
            }
        	finalData = BucketTools.toByteArray(sourceData);
        }

        return new Compressed(finalData, compressionAlgorithm);
    }
    
    public byte[] getRoutingKey() {
    	return routingKey;
    }

    // Not just the routing key, enough data to reconstruct the key (excluding any pubkey needed)
    public byte[] getKeyBytes() {
    	return routingKey;
    }

	public static ClientKeyBlock createKeyBlock(ClientKey key, KeyBlock block) throws KeyVerifyException {
		if(key instanceof ClientSSK)
			return ClientSSKBlock.construct((SSKBlock)block, (ClientSSK)key);
		else //if(key instanceof ClientCHK
			return new ClientCHKBlock((CHKBlock)block, (ClientCHK)key);
	}
}
