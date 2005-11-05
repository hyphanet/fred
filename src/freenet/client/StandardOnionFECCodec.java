package freenet.client;

import java.io.InputStream;
import java.io.OutputStream;

import com.onionnetworks.fec.DefaultFECCodeFactory;
import com.onionnetworks.fec.FECCode;

import freenet.client.Segment.BlockStatus;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.Fields;
import freenet.support.LRUHashBag;
import freenet.support.LRUHashtable;

/**
 * FECCodec implementation using the onion code.
 */
public class StandardOnionFECCodec extends FECCodec {

	// REDFLAG: How big is one of these?
	private static int MAX_CACHED_CODECS = 16;
	// REDFLAG: Optimal stripe size? Smaller => less memory usage, but more JNI overhead
	private static int STRIPE_SIZE = 4096;
	
	private static class MyKey {
		/** Number of input blocks */
		int k;
		/** Number of output blocks, including input blocks */
		int n;
		
		public MyKey(int n, int k) {
			this.n = n;
			this.k = k;
		}
		
		public boolean equals(Object o) {
			if(o instanceof MyKey) {
				MyKey key = (MyKey)o;
				return key.n == n && key.k == k;
			} else return false;
		}
		
		public int hashCode() {
			return (n << 16) + k;
		}
	}

	private static LRUHashtable recentlyUsedCodecs;
	
	public synchronized static FECCodec getInstance(int dataBlocks, int checkBlocks) {
		MyKey key = new MyKey(dataBlocks, checkBlocks + dataBlocks);
		StandardOnionFECCodec codec = (StandardOnionFECCodec) recentlyUsedCodecs.get(key);
		if(codec != null) {
			recentlyUsedCodecs.push(key, codec);
			return codec;
		}
		codec = new StandardOnionFECCodec(dataBlocks, checkBlocks + dataBlocks);
		recentlyUsedCodecs.push(key, codec);
		while(recentlyUsedCodecs.size() > MAX_CACHED_CODECS) {
			recentlyUsedCodecs.popKey();
		}
		return codec;
	}

	private FECCode code;

	private int k;
	private int n;
	
	public StandardOnionFECCodec(int k, int n) {
		code = DefaultFECCodeFactory.getDefault().createFECCode(k,n);
	}

	public void decode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) {
		// Ensure that there are only K simultaneous running decodes.
	}
	
	public void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) {
		if(dataBlockStatus.length + checkBlockStatus.length != n)
			throw new IllegalArgumentException();
		if(dataBlockStatus.length != k)
			throw new IllegalArgumentException();
		byte[][] packets = new byte[k][];
		Bucket[] buckets = new Bucket[k];
		InputStream[] readers = new InputStream[k];
		OutputStream[] writers = new OutputStream[k];
		int[] toDecode = new int[n-k];
		int numberToDecode; // can be less than n-k
		
		for(int i=0;i<n;i++)
			packets[i] = new byte[STRIPE_SIZE];
		
		for(int i=0;i<dataBlockStatus.length;i++) {
			buckets[i] = dataBlockStatus[i].getData();
			if(buckets[i] == null) {
				buckets[i] = bf.makeBucket(blockLength);
				writers[i] = buckets[i].getOutputStream();
				readers[i] = null;
				toDecode[numberToDecode++] = i;
			} else {
				writers[i] = null;
				readers[i] = buckets[i].getInputStream();
			}
		}
		for(int i=0;i<checkBlockStatus.length;i++) {
			buckets[i+k] = checkBlockStatus[i].getData();
			if(buckets[i+k] == null) {
				buckets[i+k] = bf.makeBucket(blockLength);
				writers[i+k] = buckets[i+k].getOutputStream();
				readers[i+k] = null;
				toDecode[numberToDecode++] = i+k;
			} else {
				writers[i+k] = null;
				readers[i+k] = buckets[i+k].getInputStream();
			}
		}
		
		if(numberToDecode != toDecode.length) {
			int[] newToDecode = new int[numberToDecode];
			System.arraycopy(toDecode, 0, newToDecode, 0, numberToDecode);
			toDecode = newToDecode;
		}

		int[] offsets = new int[n];
		for(int i=0;i<n;i++) offsets[i] = 0;
		
		if(numberToDecode > 0) {
			// Do the (striped) decode
			for(int offset=0;offset<packetLength;offset+=STRIPE_SIZE) {
				// Read the data in first
				for(int i=0;i<n;i++) {
					if(readers[i] != null) {
						Fields.readFully(readers[i], packets[i]);
					}
				}
				// Do the decode
				code.decode(packets, offsets, toDecode, blockLength, true);
		}
		// TODO Auto-generated method stub
		
	}

}
