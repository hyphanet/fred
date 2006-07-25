package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.io.WritableToDataOutputStream;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;
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
    static final short ALGO_AES_PCFB_256_SHA256 = 1;

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
        short type = raf.readShort();
        if(type == NodeCHK.TYPE) {
            return NodeCHK.readCHK(raf);
        } else if(type == NodeSSK.TYPE)
        	return NodeSSK.readSSK(raf);
        
        throw new IOException("Unrecognized format: "+type);
    }
    

    /**
     * Convert the key to a double between 0.0 and 1.0.
     * Normally we will hash the key first, in order to
     * make chosen-key attacks harder.
     */
    public synchronized double toNormalizedDouble() {
        if(cachedNormalizedDouble > 0) return cachedNormalizedDouble;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        md.update(routingKey);
        int TYPE = getType();
        md.update((byte)(TYPE >> 8));
        md.update((byte)TYPE);
        byte[] digest = md.digest();
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
    	if(o == null) return false;
    	return this.hash == o.hashCode();
    }
    
    static Bucket decompress(boolean isCompressed, byte[] output, BucketFactory bf, int maxLength, short compressionAlgorithm, boolean shortLength) throws CHKDecodeException, IOException {
        if(isCompressed) {
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
                throw new CHKDecodeException("Invalid precompressed size: "+len);
            Compressor decompressor = Compressor.getCompressionAlgorithmByMetadataID(compressionAlgorithm);
            Bucket inputBucket = new SimpleReadOnlyArrayBucket(output, shortLength?2:4, output.length-(shortLength?2:4));
            try {
				return decompressor.decompress(inputBucket, bf, maxLength, null);
			} catch (CompressionOutputSizeException e) {
				throw new CHKDecodeException("Too big");
			}
        } else {
        	return BucketTools.makeImmutableBucket(bf, output);
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
    
    static Compressed compress(Bucket sourceData, boolean dontCompress, short alreadyCompressedCodec, long sourceLength, long MAX_LENGTH_BEFORE_COMPRESSION, long MAX_COMPRESSED_DATA_LENGTH, boolean shortLength) throws KeyEncodeException, IOException {
    	byte[] finalData = null;
        short compressionAlgorithm = -1;
        // Try to compress it - even if it fits into the block,
        // because compressing it improves its entropy.
        if(sourceData.size() > MAX_LENGTH_BEFORE_COMPRESSION)
            throw new KeyEncodeException("Too big");
        if((!dontCompress) || (alreadyCompressedCodec >= 0)) {
        	byte[] cbuf = null;
        	if(alreadyCompressedCodec >= 0) {
           		if(sourceData.size() > MAX_COMPRESSED_DATA_LENGTH)
        			throw new KeyEncodeException("Too big (precompressed)");
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
            if(sourceData.size() > NodeCHK.BLOCK_SIZE) {
                throw new CHKEncodeException("Too big");
            }
        	finalData = BucketTools.toByteArray(sourceData);
        }

        return new Compressed(finalData, compressionAlgorithm);
    }
    
    public byte[] getRoutingKey() {
    	return routingKey;
    }
    
}
