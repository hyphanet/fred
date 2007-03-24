/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.Native8Code;
import com.onionnetworks.fec.PureCode;
import com.onionnetworks.util.Buffer;

import freenet.client.async.SplitFileFetcher;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;

/**
 * FECCodec implementation using the onion code.
 */
public class StandardOnionFECCodec extends FECCodec {

	private static boolean logMINOR;

	// REDFLAG: How big is one of these?
	private static int MAX_CACHED_CODECS = 8;
	// REDFLAG: Optimal stripe size? Smaller => less memory usage, but more JNI overhead
	private static int STRIPE_SIZE = 4096;
	
	static boolean noNative;

	private static final LRUHashtable recentlyUsedCodecs = new LRUHashtable();

	private final FECCode fec;
	private final int k, n;
	
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

	public StandardOnionFECCodec(int k, int n) {
		this.k = k;
		this.n = n;
		
		FECCode fec2 = null;
		if(!noNative) {
			try {
				fec2 = new Native8Code(k,n);
			} catch (Throwable t) {
				if(!noNative) {
					System.err.println("Failed to load native FEC: "+t);
					t.printStackTrace();
				}
				Logger.error(this, "Failed to load native FEC: "+t+" (k="+k+" n="+n+ ')', t);
				
				if(t instanceof UnsatisfiedLinkError)
					noNative = true;
			}
		}
		
		if (fec2 != null){
			fec = fec2;
		} else 	{
			fec = new PureCode(k,n);
		}

		// revert to below if above causes JVM crashes
		// Worst performance, but decode crashes
		// fec = new PureCode(k,n);
		// Crashes are caused by bugs which cause to use 320/128 etc. - n > 256, k < 256.

		fecRunnerThread = null;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	/**
	 * @deprecated
	 */
	public void decode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR) 
			Logger.minor(this, "Queueing decode: " + dataBlockStatus.length
				+ " data blocks, " + checkBlockStatus.length
				+ " check blocks, block length " + blockLength + " with "
				+ this, new Exception("debug"));
		
		realDecode(dataBlockStatus, checkBlockStatus, blockLength, bf);
	}
	
	private void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
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
							buckets[i] = BucketTools.pad(buckets[i], blockLength, bf,(int) sz);
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
						"Must have at least k packets (k="+k+",idx="+idx+ ')');

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
					fec.decode(packets, disposableIndexes);
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

	/**
	 * @deprecated
	 */
	public void encode(Bucket[] dataBlockStatus, Bucket[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR)
			Logger.minor(this, "Queueing encode: " + dataBlockStatus.length
					+ " data blocks, " + checkBlockStatus.length
					+ " check blocks, block length " + blockLength + " with "
					+ this, new Exception("debug"));

		Thread currentThread = Thread.currentThread();
		final int currentThreadPriority = currentThread.getPriority();
		final String oldThreadName = currentThread.getName();

		currentThread.setName("Encoder thread for k="+k+", n="+n+" enabled at "+System.currentTimeMillis());
		currentThread.setPriority(Thread.MIN_PRIORITY);

		long startTime = System.currentTimeMillis();
		realEncode(dataBlockStatus, checkBlockStatus, blockLength, bf);
		long endTime = System.currentTimeMillis();
		if(logMINOR)
			Logger.minor(this, "Splitfile encode: k="+k+", n="+n+" encode took "+(endTime-startTime)+"ms");

		currentThread.setName(oldThreadName);
		currentThread.setPriority(currentThreadPriority);
	}
	
	/**
	 * @deprecated
	 */
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
						buckets[i] = BucketTools.pad(buckets[i], blockLength, bf, (int) sz);
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
					fec.encode(dataPackets, checkPackets, toEncode);
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

	public int countCheckBlocks() {
		return n-k;
	}
	
	// ###############################
	
	public void addToQueue(Bucket[] dataBlocks, Bucket[] checkBlocks, int blockLength, BucketFactory bucketFactory, StandardOnionFECCodecEncoderCallback callback, boolean isADecodingJob){
		synchronized (_awaitingJobs) {
			if((fecRunnerThread == null) || !fecRunnerThread.isAlive()){
				if(fecRunnerThread != null) Logger.error(this, "The callback died!! restarting a new one, please report that error.");
				fecRunnerThread = new Thread(fecRunner, "FEC Pool");
				fecRunnerThread.setDaemon(true);
				fecRunnerThread.setPriority(Thread.MIN_PRIORITY);
				
				fecRunnerThread.start();
			}
			
			_awaitingJobs.addFirst(new FECJob(dataBlocks, checkBlocks, blockLength, bucketFactory, callback, isADecodingJob));
		}
		if(logMINOR) Logger.minor(this, "Adding a new job to the queue (" +_awaitingJobs.size() + ").");
		synchronized (fecRunner){
			fecRunner.notifyAll();
		}
	}
	
	public void addToQueue(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bucketFactory, StandardOnionFECCodecEncoderCallback callback, boolean isADecodingJob){
		Bucket[] dataBlocks = new Bucket[dataBlockStatus.length];
		Bucket[] checkBlocks = new Bucket[checkBlockStatus.length];
		for(int i=0;i<dataBlocks.length;i++)
			dataBlocks[i] = dataBlockStatus[i].getData();
		for(int i=0;i<checkBlocks.length;i++)
			checkBlocks[i] = checkBlockStatus[i].getData();
		addToQueue(dataBlocks, checkBlocks, blockLength, bucketFactory, callback, isADecodingJob);
		for(int i=0;i<dataBlocks.length;i++)
			dataBlockStatus[i].setData(dataBlocks[i]);
		for(int i=0;i<checkBlocks.length;i++)
			checkBlockStatus[i].setData(checkBlocks[i]);
	}
	
	private final LinkedList _awaitingJobs = new LinkedList();
	private final FECRunner fecRunner = new FECRunner();
	private Thread fecRunnerThread;
	
	public interface StandardOnionFECCodecEncoderCallback{
		public void onEncodedSegment();
		public void onDecodedSegment();
	}
	
	private class FECJob {
		final Bucket[] dataBlocks, checkBlocks;
		final BucketFactory bucketFactory;
		final int blockLength;
		final StandardOnionFECCodecEncoderCallback callback;
		final boolean isADecodingJob;
		
		FECJob(Bucket[] dataBlocks, Bucket[] checkBlocks, int blockLength, BucketFactory bucketFactory, StandardOnionFECCodecEncoderCallback callback, boolean isADecodingJob) {
			this.dataBlocks = dataBlocks;
			this.checkBlocks = checkBlocks;
			this.blockLength = blockLength;
			this.bucketFactory = bucketFactory;
			this.callback = callback;
			this.isADecodingJob = isADecodingJob;
		}
	}
	
	private class FECRunner implements Runnable {		
		public void run(){
			while(true){
				FECJob job = null;
				// Get a job
				synchronized (_awaitingJobs) {
					job = (FECJob) _awaitingJobs.removeLast();
				}
				
				if(job != null){
					// Encode it
					try {
						if(job.isADecodingJob)
							realDecode(job.dataBlocks, job.checkBlocks, job.blockLength, job.bucketFactory);
						else
							realEncode(job.dataBlocks, job.checkBlocks, job.blockLength, job.bucketFactory);
					} catch (IOException e) {
						Logger.error(this, "BOH! ioe:" + e.getMessage());
					}
					// Call the callback
					try {
						if(job.isADecodingJob)
							job.callback.onDecodedSegment();
						else
							job.callback.onEncodedSegment();
					} catch (Throwable e) {
						Logger.error(this, "The callback failed!" + e.getMessage());
					}
				} else {
					try {
						synchronized (this) {
							wait(Integer.MAX_VALUE);	
						}
					} catch (InterruptedException e) {}
				}
			}
		}
	}
}
