package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.onionnetworks.fec.DefaultFECCodeFactory;
import com.onionnetworks.fec.FECCode;
import com.onionnetworks.util.Buffer;

import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.LRUHashtable;

/**
 * FECCodec implementation using the onion code.
 */
public class StandardOnionFECCodec extends FECCodec {

	// REDFLAG: How big is one of these?
	private static int MAX_CACHED_CODECS = 16;
	// REDFLAG: Optimal stripe size? Smaller => less memory usage, but more JNI overhead
	private static int STRIPE_SIZE = 4096;
	// REDFLAG: Make this configurable, maybe make it depend on # CPUs
	private static int PARALLEL_DECODES = 1;
	
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

	private static Object runningDecodesSync = new Object();
	private static int runningDecodes;
	
	public void decode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		// Ensure that there are only K simultaneous running decodes.
		synchronized(runningDecodesSync) {
			while(runningDecodes >= PARALLEL_DECODES) {
				try {
					wait();
				} catch (InterruptedException e) {
					// Ignore
				}
			}
			runningDecodes++;
		}
		try {
			realDecode(dataBlockStatus, checkBlockStatus, blockLength, bf);
		} finally {
			synchronized(runningDecodesSync) {
				runningDecodes--;
			}
		}
	}
	
	public void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		if(dataBlockStatus.length + checkBlockStatus.length != n)
			throw new IllegalArgumentException();
		if(dataBlockStatus.length != k)
			throw new IllegalArgumentException();
		Buffer[] packets = new Buffer[k];
		Bucket[] buckets = new Bucket[k];
		DataInputStream[] readers = new DataInputStream[k];
		OutputStream[] writers = new OutputStream[k];
		int[] toDecode = new int[n-k];
		int numberToDecode = 0; // can be less than n-k
		
		byte[] realBuffer = new byte[k * STRIPE_SIZE];
		
		for(int i=0;i<n;i++)
			packets[i] = new Buffer(realBuffer, i*STRIPE_SIZE, STRIPE_SIZE);
		
		for(int i=0;i<dataBlockStatus.length;i++) {
			buckets[i] = dataBlockStatus[i].getData();
			if(buckets[i] == null) {
				buckets[i] = bf.makeBucket(blockLength);
				writers[i] = buckets[i].getOutputStream();
				readers[i] = null;
				toDecode[numberToDecode++] = i;
			} else {
				writers[i] = null;
				readers[i] = new DataInputStream(buckets[i].getInputStream());
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
				readers[i+k] = new DataInputStream(buckets[i+k].getInputStream());
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
			for(int offset=0;offset<blockLength;offset+=STRIPE_SIZE) {
				// Read the data in first
				for(int i=0;i<n;i++) {
					if(readers[i] != null) {
						readers[i].readFully(realBuffer, i*STRIPE_SIZE, STRIPE_SIZE);
					}
				}
				// Do the decode
				// Not shuffled
				code.decode(packets, offsets);
				// packets now contains an array of decoded blocks, in order
				// Write the data out
				for(int i=0;i<n;i++) {
					writers[i].write(realBuffer, i*STRIPE_SIZE, STRIPE_SIZE);
				}
			}
		}
		for(int i=0;i<k;i++) {
			writers[i].close();
			readers[i].close();
		}
	}
}

	public void decode(BlockStatus[] dataBlockStatus, BlockStatus[] checkBlockStatus, int packetLength) {
		// TODO Auto-generated method stub
		
	}
}