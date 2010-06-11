/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.db4o.ObjectContainer;
import com.onionnetworks.fec.FECCode;
import com.onionnetworks.util.Buffer;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;

/**
 * FEC (forward error correction) handler.
 * I didn't keep the old freenet.client.FEC* etc as it seemed grossly overengineered with
 * a lot of code there only because of API confusion.
 * @author root
 *
 */
public abstract class FECCodec {

	// REDFLAG: Optimal stripe size? Smaller => less memory usage, but more JNI overhead

	private static int STRIPE_SIZE = 4096;
	static boolean logMINOR;
	protected transient FECCode fec;
	protected final int k, n;

	protected abstract void loadFEC();
	
	protected FECCodec(int k, int n) {
		this.k = k;
		this.n = n;
		if(n == 0 || n < k)
			throw new IllegalArgumentException("Invalid: k="+k+" n="+n);
	}
	
	/**
	 * Get a codec where we know both the number of data blocks and the number
	 * of check blocks, and the codec type. Normally for decoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks, int checkBlocks) {
		if(Logger.shouldLog(Logger.MINOR, FECCodec.class))
			Logger.minor(FECCodec.class, "getCodec: splitfileType="+splitfileType+" dataBlocks="+dataBlocks+" checkBlocks="+checkBlocks);
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD)
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		else
			return null;
	}

	/**
	 * Get a codec where we know only the number of data blocks and the codec
	 * type. Normally for encoding.
	 */
	public static FECCodec getCodec(short splitfileType, int dataBlocks) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			int checkBlocks = standardOnionCheckBlocks(dataBlocks);
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		}
		else
			return null;
	}
	
	private static int standardOnionCheckBlocks(int dataBlocks) {
		/**
		 * ALCHEMY: What we do know is that redundancy by FEC is much more efficient than 
		 * redundancy by simply duplicating blocks, for obvious reasons (see e.g. Wuala). But
		 * we have to have some redundancy at the duplicating blocks level because we do use
		 * some keys directly etc: we store an insert in 3 nodes. We also cache it on 20 nodes,
		 * but generally the key will fall out of the caches within days. So long term, it's 3.
		 * Multiplied by 2 here, makes 6. Used to be 1.5 * 3 = 4.5. Wuala uses 5, but that's 
		 * all FEC.
		 */
		int checkBlocks = dataBlocks * HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT / HighLevelSimpleClientImpl.SPLITFILE_SCALING_BLOCKS_PER_SEGMENT;
		if(dataBlocks >= HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT) 
			checkBlocks = HighLevelSimpleClientImpl.SPLITFILE_CHECK_BLOCKS_PER_SEGMENT;
		// An extra block for anything below the limit.
		checkBlocks++;
		// Keep it within 256 blocks.
		if(dataBlocks < 256 && dataBlocks + checkBlocks > 256)
			checkBlocks = 256 - dataBlocks;
		return checkBlocks;
	}

	public static int getCheckBlocks(short splitfileType, int dataBlocks) {
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			return standardOnionCheckBlocks(dataBlocks);
		} else
			return 0;
	}

	/**
	 * How many check blocks?
	 */
	public abstract int countCheckBlocks();

	protected void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		loadFEC();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "Doing decode: " + dataBlockStatus.length + " data blocks, " + checkBlockStatus.length + " check blocks, block length " + blockLength + " with " + this, new Exception("debug"));
		if(dataBlockStatus.length + checkBlockStatus.length != n)
			throw new IllegalArgumentException();
		if(dataBlockStatus.length != k)
			throw new IllegalArgumentException();
		Buffer[] packets = new Buffer[k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[n];
		OutputStream[] writers = new OutputStream[k];
		int numberToDecode = 0; // can be less than n-k

		try {

			byte[] realBuffer = new byte[k * STRIPE_SIZE];

			int[] packetIndexes = new int[k];
			for(int i = 0; i < packetIndexes.length; i++)
				packetIndexes[i] = -1;

			int idx = 0;

			for(int i = 0; i < k; i++)
				packets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
					STRIPE_SIZE);

			// Shortcut.
			// Due to the not-fetching-last-block code, we need to check here,
			// rather than relying on numberToDecode (since the last data block won't be part of numberToDecode).
			
			boolean needDecode = false;
			for(int i = 0; i < dataBlockStatus.length;i++) {
				if(dataBlockStatus[i].getData() == null)
					needDecode = true;
			}
			
			if(!needDecode) return;
			
			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i].getData();
				if(buckets[i] == null) {
					buckets[i] = bf.makeBucket(blockLength);
					writers[i] = buckets[i].getOutputStream();
					if(logMINOR)
						Logger.minor(this, "writers[" + i + "] != null");
					readers[i] = null;
					numberToDecode++;
				}
				else {
					long sz = buckets[i].size();
					if(sz < blockLength) {
						if(i != dataBlockStatus.length - 1)
							throw new IllegalArgumentException("All buckets must be the full size (caller must pad if needed) but data bucket " + i + " of " + dataBlockStatus.length + " (" + dataBlockStatus[i] + ") is " + sz + " not " + blockLength);
					} else {
						if(logMINOR)
							Logger.minor(this, "writers[" + i + "] = null (already filled)");
						writers[i] = null;
						readers[i] = new DataInputStream(buckets[i].getInputStream());
						packetIndexes[idx++] = i;
					}
				}
			}
			for(int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i].getData();
				if(buckets[i + k] == null)
					readers[i + k] = null;
				else {
					readers[i + k] = new DataInputStream(buckets[i + k].getInputStream());
					if(idx < k)
						packetIndexes[idx++] = i + k;
				}
			}

			if(idx < k)
				throw new IllegalArgumentException("Must have at least k packets (k=" + k + ",idx=" + idx + ')');

			if(logMINOR)
				for(int i = 0; i < packetIndexes.length; i++)
					Logger.minor(this, "[" + i + "] = " + packetIndexes[i]);

			if(numberToDecode > 0)
				// Do the (striped) decode

				for(int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					// Read the data in first
					for(int i = 0; i < k; i++) {
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
					for(int i = 0; i < k; i++)
						if(writers[i] != null)
							writers[i].write(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
				}

		}
		finally {
			for(int i = 0; i < k; i++)
				Closer.close(writers[i]);
			for(int i = 0; i < n; i++)
				Closer.close(readers[i]);
		}
		// Set new buckets only after have a successful decode.
		// Note that the last data bucket will be overwritten padded.
		for(int i = 0; i < dataBlockStatus.length; i++) {
			Bucket data = buckets[i];
			if(data.size() != blockLength)
				throw new IllegalStateException("Block " + i + ": " + data + " : " + dataBlockStatus[i] + " length " + data.size() + " whereas blockLength="+blockLength);
			dataBlockStatus[i].setData(data);
		}
	}

	/**
	 * Do the actual encode.
	 */
	protected void realEncode(Bucket[] dataBlockStatus,
		Bucket[] checkBlockStatus, int blockLength, BucketFactory bf)
		throws IOException {
		if(bf == null) throw new NullPointerException();
		loadFEC();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
//		Runtime.getRuntime().gc();
//		Runtime.getRuntime().runFinalization();
		long memUsedAtStart = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		if(logMINOR) {
			Logger.minor(this, "Memory in use at start: " + memUsedAtStart + " max=" + Runtime.getRuntime().maxMemory());
			Logger.minor(this, "Doing encode: " + dataBlockStatus.length + " data blocks, " + checkBlockStatus.length + " check blocks, block length " + blockLength + " with " + this);
		}
		if((dataBlockStatus.length + checkBlockStatus.length != n) ||
			(dataBlockStatus.length != k))
			throw new IllegalArgumentException("Data blocks: " + dataBlockStatus.length + ", Check blocks: " + checkBlockStatus.length + ", n: " + n + ", k: " + k);
		Buffer[] dataPackets = new Buffer[k];
		Buffer[] checkPackets = new Buffer[n - k];
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[k];
		OutputStream[] writers = new OutputStream[n - k];
		
		try {

			int[] toEncode = new int[n - k];
			int numberToEncode = 0; // can be less than n-k

			byte[] realBuffer = new byte[n * STRIPE_SIZE];

			for(int i = 0; i < k; i++)
				dataPackets[i] = new Buffer(realBuffer, i * STRIPE_SIZE,
					STRIPE_SIZE);
			for(int i = 0; i < n - k; i++)
				checkPackets[i] = new Buffer(realBuffer, (i + k) * STRIPE_SIZE,
					STRIPE_SIZE);

			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i];
				if(buckets[i] == null)
					throw new NullPointerException("Data bucket "+i+" is null!");
				long sz = buckets[i].size();
				if(sz < blockLength) {
					throw new IllegalArgumentException("All buckets must be the full size: caller must pad the last one if needed");
				}
				readers[i] = new DataInputStream(buckets[i].getInputStream());
			}

			int created = 0;
			for(int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i];
				if(buckets[i + k] == null) {
					buckets[i + k] = bf.makeBucket(blockLength);
					writers[i] = buckets[i + k].getOutputStream();
					toEncode[numberToEncode++] = i + k;
					created++;
				}
				else
					writers[i] = null;
			}
			if(logMINOR)
				Logger.minor(this, "Created "+created+" check buckets");

			//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
//			Runtime.getRuntime().gc();
//			Runtime.getRuntime().runFinalization();
			long memUsedBeforeEncodes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(logMINOR)
				Logger.minor(this, "Memory in use before encodes: " + memUsedBeforeEncodes);

			if(numberToEncode > 0)
				// Do the (striped) encode

				for(int offset = 0; offset < blockLength; offset += STRIPE_SIZE) {
					long memUsedBeforeRead = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use before read: " + memUsedBeforeRead);
					// Read the data in first
					for(int i = 0; i < k; i++)
						readers[i].readFully(realBuffer, i * STRIPE_SIZE,
							STRIPE_SIZE);
					// Do the encode
					// Not shuffled
					long startTime = System.currentTimeMillis();
					//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedBeforeStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use before stripe: " + memUsedBeforeStripe);
					fec.encode(dataPackets, checkPackets, toEncode);
					//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
//					Runtime.getRuntime().gc();
//					Runtime.getRuntime().runFinalization();
					long memUsedAfterStripe = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use after stripe: " + memUsedAfterStripe);
					long endTime = System.currentTimeMillis();
					if(logMINOR)
						Logger.minor(this, "Stripe encode took " + (endTime - startTime) + "ms for k=" + k + ", n=" + n + ", stripeSize=" + STRIPE_SIZE);
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for(int i = k; i < n; i++)
						if(writers[i - k] != null)
							writers[i - k].write(realBuffer, i * STRIPE_SIZE,
								STRIPE_SIZE);
				}

		}
		finally {
			for(int i = 0; i < k; i++)
				Closer.close(readers[i]);
			for(int i = 0; i < n - k; i++)
				Closer.close(writers[i]);
		}
		// Set new buckets only after have a successful decode.
		for(int i = 0; i < checkBlockStatus.length; i++) {
			Bucket data = buckets[i + k];
			if(data == null)
				throw new NullPointerException();
			checkBlockStatus[i] = data;
		}
	}

	/**
	 * The method used to submit {@link FECJob}s to the pool
	 * 
	 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
	 * 
	 * @param FECJob
	 */
	public void addToQueue(FECJob job, FECQueue queue, ObjectContainer container) {
		queue.addToQueue(job, this, container);
	}
	
	public void objectCanDeactivate(ObjectContainer container) {
		Logger.minor(this, "Deactivating "+this, new Exception("debug"));
	}

	public abstract short getAlgorithm();
}
