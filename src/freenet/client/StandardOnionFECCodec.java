package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.spaceroots.mantissa.random.MersenneTwister;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.Native8Code;
import com.onionnetworks.fec.PureCode;
import com.onionnetworks.util.Buffer;

import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.io.Bucket;
import freenet.support.io.BucketFactory;
import freenet.support.io.BucketTools;

/**
 * FECCodec implementation using the onion code.
 */
public class StandardOnionFECCodec extends FECCodec {

	private static boolean logMINOR;
	
	public class Encoder implements Runnable {

		private final Bucket[] dataBlockStatus, checkBlockStatus;
		private final int blockLength;
		private final BucketFactory bf;
		private IOException thrownIOE;
		private RuntimeException thrownRE;
		private Error thrownError;
		private boolean finished;
		
		public Encoder(Bucket[] dataBlockStatus, Bucket[] checkBlockStatus, int blockLength, BucketFactory bf) {
			this.dataBlockStatus = dataBlockStatus;
			this.checkBlockStatus = checkBlockStatus;
			this.blockLength = blockLength;
			this.bf = bf;
		}

		public void run() {
			long startTime = System.currentTimeMillis();
			try {
				realEncode(dataBlockStatus, checkBlockStatus, blockLength, bf);
			} catch (IOException e) {
				thrownIOE = e;
			} catch (RuntimeException e) {
				thrownRE = e;
			} catch (Error e) {
				thrownError = e;
			}
			long endTime = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "Splitfile encode: k="+k+", n="+n+" encode took "+(endTime-startTime)+"ms");
			finished = true;
			synchronized(this) {
				notify();
			}
		}

		public void throwException() throws IOException {
			if(thrownIOE != null)
				throw thrownIOE;
			if(thrownRE != null)
				throw thrownRE;
			if(thrownError != null)
				throw thrownError;
		}

	}

	// REDFLAG: How big is one of these?
	private static int MAX_CACHED_CODECS = 8;
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
				return (key.n == n) && (key.k == k);
			} else return false;
		}
		
		public int hashCode() {
			return (n << 16) + k;
		}
	}

	private static LRUHashtable recentlyUsedCodecs = new LRUHashtable();
	
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

	private final FECCode encoder;
	private final FECCode decoder;

	private final int k;
	private final int n;
	
	static boolean noNative;
	
	public StandardOnionFECCodec(int k, int n) {
		this.k = k;
		this.n = n;
		// Best performance, doesn't crash
		//encoder = FECCodeFactory.getDefault().createFECCode(k,n);
		FECCode fec;
		try {
			fec = new Native8Code(k,n);
		} catch (Throwable t) {
			if(!noNative) {
				System.err.println("Failed to load native FEC: "+t);
				t.printStackTrace();
			}
			Logger.error(this, "Failed to load native FEC: "+t, t);
			fec = new PureCode(k,n);
			noNative = true;
		}
		encoder = fec;
		// revert to below if above causes JVM crashes
		// Worst performance, but decode crashes
		//decoder = new PureCode(k,n);
		// Crashes are caused by bugs which cause to use 320/128 etc. - n > 256, k < 256.
		decoder = encoder;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	private static Object runningDecodesSync = new Object();
	private static int runningDecodes;

	public void decode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR) 
			Logger.minor(this, "Queueing decode: " + dataBlockStatus.length
				+ " data blocks, " + checkBlockStatus.length
				+ " check blocks, block length " + blockLength + " with "
				+ this, new Exception("debug"));
		// Ensure that there are only K simultaneous running decodes.
		synchronized(runningDecodesSync) {
			while(runningDecodes >= PARALLEL_DECODES) {
				try {
					runningDecodesSync.wait(10*1000);
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
				runningDecodesSync.notify();
			}
		}
	}
	
	public void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		if(logMINOR)
			Logger.minor(this, "Doing decode: " + dataBlockStatus.length
					+ " data blocks, " + checkBlockStatus.length
					+ " check blocks, block length " + blockLength + " with "
					+ this, new Exception("debug"));
		if (dataBlockStatus.length + checkBlockStatus.length != n)
			throw new IllegalArgumentException();
		if (dataBlockStatus.length != k)
			throw new IllegalArgumentException();
		Buffer[] packets = new Buffer[k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[n];
		OutputStream[] writers = new OutputStream[k];
		int numberToDecode = 0; // can be less than n-k

		try {

			byte[] realBuffer = new byte[k * STRIPE_SIZE];

			int[] packetIndexes = new int[k];
			for (int i = 0; i < packetIndexes.length; i++)
				packetIndexes[i] = -1;

			int idx = 0;

			for (int i = 0; i < k; i++)
				packets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
						STRIPE_SIZE);

			for (int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i].getData();
				if (buckets[i] == null) {
					buckets[i] = bf.makeBucket(blockLength);
					writers[i] = buckets[i].getOutputStream();
					if(logMINOR) Logger.minor(this, "writers[" + i + "] != null");
					readers[i] = null;
					numberToDecode++;
				} else {
					long sz = buckets[i].size();
					if (sz < blockLength) {
						if (i != dataBlockStatus.length - 1)
							throw new IllegalArgumentException(
									"All buckets except the last must be the full size but data bucket "
											+ i + " of "
											+ dataBlockStatus.length + " ("
											+ dataBlockStatus[i] + ") is " + sz
											+ " not " + blockLength);
						if (sz < blockLength)
							buckets[i] = pad(buckets[i], blockLength, bf,
									(int) sz);
						else
							throw new IllegalArgumentException("Too big: " + sz
									+ " bigger than " + blockLength);
					}
					if(logMINOR) Logger.minor(this, "writers[" + i + "] = null (already filled)");
					writers[i] = null;
					readers[i] = new DataInputStream(buckets[i]
							.getInputStream());
					packetIndexes[idx++] = i;
				}
			}
			for (int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i].getData();
				if (buckets[i + k] == null) {
					readers[i + k] = null;
				} else {
					readers[i + k] = new DataInputStream(buckets[i + k]
							.getInputStream());
					if (idx < k)
						packetIndexes[idx++] = i + k;
				}
			}

			if (idx < k)
				throw new IllegalArgumentException(
						"Must have at least k packets (k="+k+",idx="+idx+")");

			if(logMINOR) for (int i = 0; i < packetIndexes.length; i++)
				Logger.minor(this, "[" + i + "] = " + packetIndexes[i]);

			if (numberToDecode > 0) {
				// Do the (striped) decode
				for (int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					// Read the data in first
					for (int i = 0; i < k; i++) {
						int x = packetIndexes[i];
						readers[x].readFully(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
					}
					// Do the decode
					// Not shuffled
					int[] disposableIndexes = new int[packetIndexes.length];
					System.arraycopy(packetIndexes, 0, disposableIndexes, 0,
							packetIndexes.length);
					decoder.decode(packets, disposableIndexes);
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for (int i = 0; i < k; i++) {
						if (writers[i] != null)
							writers[i].write(realBuffer, i * STRIPE_SIZE,
									STRIPE_SIZE);
					}
				}
			}

		} finally {
			
			for (int i = 0; i < k; i++) {
				if (writers[i] != null)
					writers[i].close();
			}
			for (int i = 0; i < n; i++) {
				if (readers[i] != null)
					readers[i].close();
			}

		}
		// Set new buckets only after have a successful decode.
		// Note that the last data bucket will be overwritten padded.
		for (int i = 0; i < dataBlockStatus.length; i++) {
			Bucket data = buckets[i];
			if (data.size() != blockLength)
				throw new IllegalStateException("Block " + i + ": " + data
						+ " : " + dataBlockStatus[i] + " length " + data.size());
			dataBlockStatus[i].setData(data);
		}
	}

	public void encode(Bucket[] dataBlockStatus, Bucket[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR)
			Logger.minor(this, "Queueing encode: " + dataBlockStatus.length
					+ " data blocks, " + checkBlockStatus.length
					+ " check blocks, block length " + blockLength + " with "
					+ this, new Exception("debug"));
		// Encodes count as decodes.
		synchronized(runningDecodesSync) {
			while(runningDecodes >= PARALLEL_DECODES) {
				try {
					runningDecodesSync.wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
			runningDecodes++;
		}
		try {
			// Run on a separate thread so we can tweak priority
			Encoder et = new Encoder(dataBlockStatus, checkBlockStatus, blockLength, bf);
			Thread t = new Thread(et, "Encoder thread for k="+k+", n="+n+" started at "+System.currentTimeMillis());
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
			synchronized(et) {
				while(!et.finished) {
					try {
						et.wait(10*1000);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}
			et.throwException();
		} finally {
			synchronized(runningDecodesSync) {
				runningDecodes--;
				runningDecodesSync.notify();
			}
		}
	}
	
	public void encode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		Bucket[] dataBlocks = new Bucket[dataBlockStatus.length];
		Bucket[] checkBlocks = new Bucket[checkBlockStatus.length];
		for(int i=0;i<dataBlocks.length;i++)
			dataBlocks[i] = dataBlockStatus[i].getData();
		for(int i=0;i<checkBlocks.length;i++)
			checkBlocks[i] = checkBlockStatus[i].getData();
		encode(dataBlocks, checkBlocks, blockLength, bf);
		for(int i=0;i<dataBlocks.length;i++)
			dataBlockStatus[i].setData(dataBlocks[i]);
		for(int i=0;i<checkBlocks.length;i++)
			checkBlockStatus[i].setData(checkBlocks[i]);
	}

	/**
	 * Do the actual encode.
	 */
	private void realEncode(Bucket[] dataBlockStatus,
			Bucket[] checkBlockStatus, int blockLength, BucketFactory bf)
			throws IOException {
//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
		long memUsedAtStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		if(logMINOR) {
			Logger.minor(this, "Memory in use at start: "+memUsedAtStart+" max="+Runtime.getRuntime().maxMemory());
			Logger.minor(this, "Doing encode: " + dataBlockStatus.length
					+ " data blocks, " + checkBlockStatus.length
					+ " check blocks, block length " + blockLength + " with "
					+ this);
		}
		if ((dataBlockStatus.length + checkBlockStatus.length != n) ||
				(dataBlockStatus.length != k))
			throw new IllegalArgumentException("Data blocks: "+dataBlockStatus.length+", Check blocks: "+checkBlockStatus.length+", n: "+n+", k: "+k);
		Buffer[] dataPackets = new Buffer[k];
		Buffer[] checkPackets = new Buffer[n - k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[k];
		OutputStream[] writers = new OutputStream[n - k];

		try {

			int[] toEncode = new int[n - k];
			int numberToEncode = 0; // can be less than n-k

			byte[] realBuffer = new byte[n * STRIPE_SIZE];

			for (int i = 0; i < k; i++)
				dataPackets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
						STRIPE_SIZE);
			for (int i = 0; i < n - k; i++)
				checkPackets[i] = new Buffer(realBuffer, (i + k) * STRIPE_SIZE,
						STRIPE_SIZE);

			for (int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i];
				long sz = buckets[i].size();
				if (sz < blockLength) {
					if (i != dataBlockStatus.length - 1)
						throw new IllegalArgumentException(
								"All buckets except the last must be the full size");
					if (sz < blockLength)
						buckets[i] = pad(buckets[i], blockLength, bf, (int) sz);
					else
						throw new IllegalArgumentException("Too big: " + sz
								+ " bigger than " + blockLength);
				}
				readers[i] = new DataInputStream(buckets[i].getInputStream());
			}

			for (int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i];
				if (buckets[i + k] == null) {
					buckets[i + k] = bf.makeBucket(blockLength);
					writers[i] = buckets[i + k].getOutputStream();
					toEncode[numberToEncode++] = i + k;
				} else {
					writers[i] = null;
				}
			}

//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
			long memUsedBeforeEncodes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(logMINOR) Logger.minor(this, "Memory in use before encodes: "+memUsedBeforeEncodes);
			
			if (numberToEncode > 0) {
				// Do the (striped) encode
				for (int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					long memUsedBeforeRead = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR) Logger.minor(this, "Memory in use before read: "+memUsedBeforeRead);
					// Read the data in first
					for (int i = 0; i < k; i++) {
						readers[i].readFully(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
					}
					// Do the encode
					// Not shuffled
					long startTime = System.currentTimeMillis();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedBeforeStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR) Logger.minor(this, "Memory in use before stripe: "+memUsedBeforeStripe);
					encoder.encode(dataPackets, checkPackets, toEncode);
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedAfterStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR) Logger.minor(this, "Memory in use after stripe: "+memUsedAfterStripe);
					long endTime = System.currentTimeMillis();
					if(logMINOR) Logger.minor(this, "Stripe encode took "
							+ (endTime - startTime) + "ms for k=" + k + ", n="
							+ n + ", stripeSize=" + STRIPE_SIZE);
					// Try to limit CPU usage!!
					try {
						Thread.sleep(endTime - startTime);
					} catch (InterruptedException e) {
						// Ignore
					}
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for (int i = k; i < n; i++) {
						if (writers[i - k] != null)
							writers[i - k].write(realBuffer, i * STRIPE_SIZE,
									STRIPE_SIZE);
					}
				}
			}

		} finally {

			for (int i = 0; i < k; i++)
				if (readers[i] != null)
					readers[i].close();
			for (int i = 0; i < n - k; i++)
				if (writers[i] != null)
					writers[i].close();

		}
		// Set new buckets only after have a successful decode.
		for (int i = 0; i < checkBlockStatus.length; i++) {
			Bucket data = buckets[i + k];
			if (data == null)
				throw new NullPointerException();
			checkBlockStatus[i] = data;
		}
	}

	private Bucket pad(Bucket oldData, int blockLength, BucketFactory bf, int l) throws IOException {
		// FIXME move somewhere else?
		byte[] hash = BucketTools.hash(oldData);
		Bucket b = bf.makeBucket(blockLength);
		MersenneTwister mt = new MersenneTwister(hash);
		OutputStream os = b.getOutputStream();
		BucketTools.copyTo(oldData, os, l);
		byte[] buf = new byte[4096];
		for(int x=l;x<blockLength;) {
			int remaining = blockLength - x;
			int thisCycle = Math.min(remaining, buf.length);
			mt.nextBytes(buf); // FIXME??
			os.write(buf, 0, thisCycle);
			x += thisCycle;
		}
		os.close();
		if(b.size() != blockLength)
			throw new IllegalStateException();
		return b;
	}

	public int countCheckBlocks() {
		return n-k;
	}
}
