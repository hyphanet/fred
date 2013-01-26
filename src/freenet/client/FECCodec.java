/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.db4o.ObjectContainer;
import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.Native8Code;
import com.onionnetworks.util.Buffer;

import freenet.client.InsertContext.CompatibilityMode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
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

	protected transient FECCode fec;
	protected final int k, n;
	// Striping is very costly I/O wise.
	// So set a maximum buffer size and calculate the stripe size accordingly.
	static final int MAX_MEMORY_BUFFER = 8*1024*1024;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

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
		if(logMINOR)
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
	public static FECCodec getCodec(short splitfileType, int dataBlocks, CompatibilityMode compatibilityMode) {
		if(splitfileType == Metadata.SPLITFILE_NONREDUNDANT)
			return null;
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			int checkBlocks = standardOnionCheckBlocks(dataBlocks, compatibilityMode);
			return StandardOnionFECCodec.getInstance(dataBlocks, checkBlocks);
		}
		else
			return null;
	}
	
	private static int standardOnionCheckBlocks(int dataBlocks, CompatibilityMode compatibilityMode) {
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
		if(compatibilityMode == InsertContext.CompatibilityMode.COMPAT_1250 || compatibilityMode == InsertContext.CompatibilityMode.COMPAT_1250_EXACT) {
			// Pre-1250, redundancy was always 100% or less.
			// Builds of that period using the native FEC (ext #26) will segfault sometimes on >100% redundancy.
			// So limit check blocks to data blocks.
			if(checkBlocks > dataBlocks) checkBlocks = dataBlocks;
		}
		return checkBlocks;
	}

	public static int getCheckBlocks(short splitfileType, int dataBlocks, CompatibilityMode compatibilityMode) {
		if(splitfileType == Metadata.SPLITFILE_ONION_STANDARD) {
			return standardOnionCheckBlocks(dataBlocks, compatibilityMode);
		} else
			return 0;
	}

	/**
	 * How many check blocks?
	 */
	public abstract int countCheckBlocks();

	protected void realDecode(SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus, int blockLength, BucketFactory bf) throws IOException {
		loadFEC();
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
		boolean[] toWrite = new boolean[k];
		
		int stripeSize = MAX_MEMORY_BUFFER / k;
		if(stripeSize > blockLength)
			stripeSize = blockLength;
		// Must be even if 16-bit code.
		if((k > 256 || n > 256) && ((stripeSize & 1) == 1))
			stripeSize++;
		if(stripeSize != 32768) System.out.println("Stripe size is "+stripeSize);

		try {

			byte[] realBuffer = new byte[k * stripeSize];

			int[] packetIndexes = new int[k];
			for(int i = 0; i < packetIndexes.length; i++)
				packetIndexes[i] = -1;

			int idx = 0;

			for(int i = 0; i < k; i++)
				packets[i] = new Buffer(realBuffer, i * stripeSize,
					stripeSize);

			// Due to the not-fetching-last-block code, we need to check here.
			
			boolean needDecode = false;
			for(int i = 0; i < dataBlockStatus.length;i++) {
				if(dataBlockStatus[i].getData() == null) needDecode = true;
			}
			
			if(!needDecode) return;
			
			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i].getData();
				if(buckets[i] == null) {
					buckets[i] = bf.makeBucket(blockLength);
					if(stripeSize != blockLength) {
						writers[i] = buckets[i].getOutputStream();
					}
					toWrite[i] = true;
					if(logMINOR)
						Logger.minor(this, "writers[" + i + "] != null");
					readers[i] = null;
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
				if(buckets[i + k] == null) {
					readers[i + k] = null;
				} else {
					if(stripeSize != blockLength) {
						readers[i + k] = new DataInputStream(buckets[i + k].getInputStream());
					}
					if(idx < k) {
						packetIndexes[idx++] = i + k;
					}
				}
			}

			if(idx < k)
				throw new IllegalArgumentException("Must have at least k packets (k=" + k + ",idx=" + idx + ')');

			if(logMINOR)
				for(int i = 0; i < packetIndexes.length; i++)
					Logger.minor(this, "[" + i + "] = " + packetIndexes[i]);

			if(fec instanceof Native8Code) {
				System.out.println("Decoding with native code, n = "+n+" k = "+k);
				System.out.flush();
			}
				
			for(int offset = 0; offset < blockLength; offset += stripeSize) {
				if(offset + stripeSize > blockLength) {
					stripeSize = blockLength - offset;
				}
				// Read the data in first
				for(int i = 0; i < k; i++) {
					int x = packetIndexes[i];
					DataInputStream dis;
					if(stripeSize == blockLength)
						dis = new DataInputStream(buckets[x].getInputStream());
					else
						dis = readers[x];
					try {
						dis.readFully(realBuffer, i * stripeSize,
								stripeSize);
					} finally {
						if(stripeSize == blockLength) {
							dis.close();
						}
					}
				}
				// Do the decode
				// Not shuffled
				int[] disposableIndexes = packetIndexes.clone();
				fec.decode(packets, disposableIndexes);
				// packets now contains an array of decoded blocks, in order
				// Write the data out
				for(int i = 0; i < k; i++) {
					if(toWrite[i]) {
						OutputStream os;
						if(stripeSize == blockLength) {
							os = buckets[i].getOutputStream();
						} else {
							os = writers[i];
						}
						try {
							os.write(realBuffer, i * stripeSize,
									stripeSize);
						} finally {
							if(stripeSize == blockLength) {
								os.close();
							}
						}
					}
				}
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
			Bucket existingData = dataBlockStatus[i].trySetData(data);
			if(existingData != null && existingData != data) {
				if(logMINOR) Logger.minor(this, "Discarding block "+i+" as now unneeded");
				data.free();
			}
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
		Buffer[] checkPackets;
		Bucket[] buckets = new Bucket[n];
		DataInputStream[] readers = new DataInputStream[k];
		OutputStream[] writers = null;
		
		try {

			int[] toEncode;
			int numberToEncode = 0; // can be less than n-k

			int created = 0;
			for(int i = 0; i < checkBlockStatus.length; i++) {
				buckets[i + k] = checkBlockStatus[i];
				if(buckets[i + k] == null) {
					buckets[i + k] = bf.makeBucket(blockLength);
					numberToEncode++;
					created++;
				}
			}
			
			toEncode = new int[numberToEncode];
			checkPackets = new Buffer[numberToEncode];
			writers = new OutputStream[numberToEncode];
			
			int stripeSize = MAX_MEMORY_BUFFER / (k + numberToEncode);
			if(stripeSize > blockLength)
				stripeSize = blockLength;
			// Must be even if 16-bit code.
			if((k > 256 || n > 256) && ((stripeSize & 1) == 1))
				stripeSize++;
			if(stripeSize != 32768) System.out.println("Stripe size is "+stripeSize);

			byte[] realBuffer = new byte[(k + numberToEncode) * stripeSize];
			
			int x = 0;
			for(int i = 0; i < checkBlockStatus.length; i++) {
				if(checkBlockStatus[i] == null) {
					toEncode[x] = i + k;
					checkPackets[x] = new Buffer(realBuffer, (x + k) * stripeSize, stripeSize);
					if(stripeSize != blockLength)
						writers[x] = buckets[i + k].getOutputStream();
					x++;
				}
			}
			
			for(int i = 0; i < k; i++)
				dataPackets[i] = new Buffer(realBuffer, i * stripeSize,
					stripeSize);

			for(int i = 0; i < dataBlockStatus.length; i++) {
				buckets[i] = dataBlockStatus[i];
				if(buckets[i] == null)
					throw new NullPointerException("Data bucket "+i+" is null!");
				long sz = buckets[i].size();
				if(sz < blockLength) {
					throw new IllegalArgumentException("All buckets must be the full size: caller must pad the last one if needed");
				}
				if(stripeSize != blockLength)
					readers[i] = new DataInputStream(buckets[i].getInputStream());
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

			if(fec instanceof Native8Code) {
				System.out.println("Encoding with native code, n = "+n+" k = "+k);
				System.out.flush();
			}
			
			if(numberToEncode > 0)
				// Do the (striped) encode

				for(int offset = 0; offset < blockLength; offset += stripeSize) {
					if(offset + stripeSize > blockLength)
						stripeSize = blockLength - offset;
					long memUsedBeforeRead = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
					if(logMINOR)
						Logger.minor(this, "Memory in use before read: " + memUsedBeforeRead);
					// Read the data in first
					for(int i = 0; i < k; i++) {
						DataInputStream dis;
						if(stripeSize == blockLength)
							dis = new DataInputStream(buckets[i].getInputStream());
						else
							dis = readers[i];
						try {
						dis.readFully(realBuffer, i * stripeSize,
							stripeSize);
						} finally {
						if(stripeSize == blockLength)
							dis.close();
						}
					}
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
						Logger.minor(this, "Stripe encode took " + (endTime - startTime) + "ms for k=" + k + ", n=" + n + ", stripeSize=" + stripeSize);
					// packets now contains an array of decoded blocks, in order
					// Write the data out
					for(int i = 0; i < writers.length; i++) {
						OutputStream os;
						if(stripeSize == blockLength)
							os = buckets[toEncode[i]].getOutputStream();
						else
							os = writers[i];
						try {
						os.write(realBuffer, (i + k) * stripeSize, stripeSize);
						} finally {
						if(stripeSize == blockLength)
							os.close();
						}
					}
				}

		}
		finally {
			for(int i = 0; i < k; i++)
				Closer.close(readers[i]);
			if(writers != null) {
				for(int i = 0; i < writers.length; i++)
					Closer.close(writers[i]);
			}
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
