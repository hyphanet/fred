/**
 * 
 */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.keys.CHKBlock;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;

/**
 * A class bundling the data meant to be FEC processed
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FECJob {
	
	private transient static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(FECJob.class);
	}
	
	private transient FECCodec codec;
	private final short fecAlgo;
	final Bucket[] dataBlocks, checkBlocks;
	final SplitfileBlock[] dataBlockStatus, checkBlockStatus;
	final BucketFactory bucketFactory;
	final int blockLength;
	final FECCallback callback;
	final boolean isADecodingJob;
	final long addedTime;
	final short priority;
	final boolean persistent;
	/** Parent queue */
	final FECQueue queue;
	// A persistent hash code helps with debugging.
	private final int hashCode;
	transient boolean running;
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	public FECJob(FECCodec codec, FECQueue queue, SplitfileBlock[] dataBlockStatus, SplitfileBlock[] checkBlockStatus,  int blockLength, BucketFactory bucketFactory, FECCallback callback, boolean isADecodingJob, short priority, boolean persistent) {
		this.codec = codec;
		this.fecAlgo = codec.getAlgorithm();
		this.queue = queue;
		this.priority = priority;
		this.addedTime = System.currentTimeMillis();
		
		this.dataBlockStatus = new SplitfileBlock[dataBlockStatus.length];
		this.checkBlockStatus = new SplitfileBlock[checkBlockStatus.length];
		for(int i=0;i<dataBlockStatus.length;i++)
			this.dataBlockStatus[i] = dataBlockStatus[i];
		for(int i=0;i<checkBlockStatus.length;i++)
			this.checkBlockStatus[i] = checkBlockStatus[i];
		
//		this.dataBlockStatus = dataBlockStatus;
//		this.checkBlockStatus = checkBlockStatus;
		
		this.dataBlocks = new Bucket[dataBlockStatus.length];
		this.checkBlocks = new Bucket[checkBlockStatus.length];
		for(int i=0;i<dataBlocks.length;i++)
			this.dataBlocks[i] = dataBlockStatus[i].getData();
		for(int i=0;i<checkBlocks.length;i++)
			this.checkBlocks[i] = checkBlockStatus[i].getData();
		
		this.blockLength = blockLength;
		this.bucketFactory = bucketFactory;
		if(bucketFactory == null)
			throw new NullPointerException();
		this.callback = callback;
		this.isADecodingJob = isADecodingJob;
		this.persistent = persistent;
		this.hashCode = super.hashCode();
	}
	
	@Override
	public String toString() {
		return super.toString()+":decode="+isADecodingJob+":callback="+callback+":persistent="+persistent;
	}
	
	public FECJob(FECCodec codec, FECQueue queue, Bucket[] dataBlocks, Bucket[] checkBlocks, int blockLength, BucketFactory bucketFactory, FECCallback callback, boolean isADecodingJob, short priority, boolean persistent) {
		this.hashCode = super.hashCode();
		this.codec = codec;
		this.fecAlgo = codec.getAlgorithm();
		this.queue = queue;
		this.priority = priority;
		this.addedTime = System.currentTimeMillis();
		
		// Make sure it is a separate array, just in case it doesn't get copied transparently by db4o.
		this.dataBlocks = new Bucket[dataBlocks.length];
		this.checkBlocks = new Bucket[checkBlocks.length];
		for(int i=0;i<dataBlocks.length;i++) {
			this.dataBlocks[i] = dataBlocks[i];
			if(!isADecodingJob) {
				if(dataBlocks[i] == null)
					throw new NullPointerException("Data block "+i+" is null for encode in FECJob constructor!");
			}
		}
		for(int i=0;i<checkBlocks.length;i++)
			this.checkBlocks[i] = checkBlocks[i];
		
		this.dataBlockStatus = null;
		this.checkBlockStatus = null;
		this.blockLength = blockLength;
		this.bucketFactory = bucketFactory;
		if(bucketFactory == null)
			throw new NullPointerException();
		this.callback = callback;
		this.isADecodingJob = isADecodingJob;
		this.persistent = persistent;
	}

	public FECCodec getCodec() {
		if(codec == null) {
			codec = FECCodec.getCodec(fecAlgo, dataBlocks.length, checkBlocks.length);
			if(codec == null)
				Logger.error(this, "No codec found for algo "+fecAlgo+" data blocks length "+dataBlocks.length+" check blocks length "+checkBlocks.length);
		}
		return codec;
	}
	
	public boolean activateForExecution(ObjectContainer container) {
		if(logMINOR) {
			Logger.minor(this, "Activating FECJob...");
			if(dataBlockStatus != null) {
				for(int i=0;i<dataBlockStatus.length;i++)
					Logger.minor(this, "Data block status "+i+": "+dataBlockStatus[i]+" (before activation)");
			}
		}
		container.activate(this, 2);
		boolean hasDataBlocks = false;
		int countDataBlocks = 0;
		int countNullDataBlocks = 0;
		if(dataBlockStatus != null) {
			hasDataBlocks = true;
			countDataBlocks = dataBlockStatus.length;
			for(int i=0;i<dataBlockStatus.length;i++) {
				container.activate(dataBlockStatus[i], 2);
				if(dataBlockStatus[i] == null)
					countNullDataBlocks++;
			}
		
			if(logMINOR) {
				for(int i=0;i<dataBlockStatus.length;i++)
					Logger.minor(this, "Data block status "+i+": "+dataBlockStatus[i]+" (after activation)");
			}
		}
		if(checkBlockStatus != null) {
			for(int i=0;i<checkBlockStatus.length;i++)
				container.activate(checkBlockStatus[i], 2);
		}
		if(dataBlocks != null) {
			hasDataBlocks = true;
			countDataBlocks = dataBlocks.length;
			for(int i=0;i<dataBlocks.length;i++) {
				container.activate(dataBlocks[i], 1);
				if(logMINOR)
					Logger.minor(this, "Data bucket "+i+": "+dataBlocks[i]+" (after activation)");
				if(dataBlocks[i] == null)
					countNullDataBlocks++;
			}
		}
		if(checkBlocks != null) {
			for(int i=0;i<checkBlocks.length;i++)
				container.activate(checkBlocks[i], 1);
		}
		if(!isADecodingJob) {
			// First find the target
			if(!hasDataBlocks) {
				Logger.error(this, "Invalid job: Encoding: No data blocks or data block status");
				return false;
			}
			if(hasDataBlocks && countDataBlocks == 0) {
				Logger.error(this, "Invalid job: Encoding: "+countDataBlocks+" blocks");
				return false;
			}
			if(hasDataBlocks && countNullDataBlocks > 0) {
				Logger.error(this, "Invalid job: Encoding: "+countDataBlocks+" blocks but "+countNullDataBlocks+" are null!");
				return false;
			}
		}
		return true;
	}

	public void deactivate(ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Deactivating FECJob...");
		if(dataBlockStatus != null) {
			for(int i=0;i<dataBlockStatus.length;i++)
				container.deactivate(dataBlockStatus[i], 2);
		}
		if(checkBlockStatus != null) {
			for(int i=0;i<checkBlockStatus.length;i++)
				container.deactivate(checkBlockStatus[i], 2);
		}
		if(dataBlocks != null) {
			for(int i=0;i<dataBlocks.length;i++)
				container.deactivate(dataBlocks[i], 1);
		}
		if(checkBlocks != null) {
			for(int i=0;i<checkBlocks.length;i++)
				container.deactivate(checkBlocks[i], 1);
		}
	}

	public void storeBlockStatuses(ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Storing block statuses");
		if(dataBlockStatus != null) {
			for(int i=0;i<dataBlockStatus.length;i++) {
				SplitfileBlock block = dataBlockStatus[i];
				if(logMINOR) Logger.minor(this, "Storing data block "+i+": "+block);
				block.storeTo(container);
			}
		}
		if(checkBlockStatus != null) {
			for(int i=0;i<checkBlockStatus.length;i++) {
				SplitfileBlock block = checkBlockStatus[i];
				if(logMINOR) Logger.minor(this, "Storing check block "+i+": "+block);
				block.storeTo(container);
			}
		}
	}

	public boolean isCancelled(ObjectContainer container) {
		if(callback == null) {
			for(Bucket data : dataBlocks) {
				if(data != null) {
					Logger.error(this, "Callback is null (deleted??) but data is valid: "+data);
					data.free();
					data.removeFrom(container);
				}
			}
			for(Bucket data : checkBlocks) {
				if(data != null) {
					Logger.error(this, "Callback is null (deleted??) but data is valid: "+data);
					data.free();
					data.removeFrom(container);
				}
			}
			for(SplitfileBlock block : dataBlockStatus) {
				if(block != null) {
					Logger.error(this, "Callback is null (deleted??) but data is valid: "+block);
					Bucket data = block.getData();
					if(data != null) {
						Logger.error(this, "Callback is null (deleted??) but data is valid: "+data);
						data.free();
						data.removeFrom(container);
					}
					container.delete(block);
				}
			}
			for(SplitfileBlock block : checkBlockStatus) {
				if(block != null) {
					Logger.error(this, "Callback is null (deleted??) but data is valid: "+block);
					Bucket data = block.getData();
					if(data != null) {
						Logger.error(this, "Callback is null (deleted??) but data is valid: "+data);
						data.free();
						data.removeFrom(container);
					}
					container.delete(block);
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * @param container
	 * @param context
	 * @return True unless we were unable to remove the job because it has already started.
	 */
	public boolean cancel(ObjectContainer container, ClientContext context) {
		return queue.cancel(this, container, context);
	}

	/** Should already be activated to depth 1 by caller. */
	public void dump(ObjectContainer container) {
		System.err.println("FEC job: "+toString());
		System.err.println("Algorithm: "+fecAlgo);
		System.err.println("Bucket factory: "+bucketFactory);
		System.err.println("Block length: "+blockLength);
		System.err.println("Callback: "+callback);
		System.err.println("Type: "+(isADecodingJob ? "DECODE" : "ENCODE"));
		System.err.println("Added time: "+addedTime);
		System.err.println("Priority: "+priority);
		System.err.println("Persistent: "+persistent);
		System.err.println("Queue: "+queue);
		System.err.println("Hash code: "+hashCode);
		System.err.println("Running: "+running);
		if(dataBlocks != null) {
			System.err.println("Has data blocks");
			int dataCount = 0;
			for(int i=0;i<dataBlocks.length;i++) {
				Bucket data = dataBlocks[i];
				if(data == null) {
					System.err.println("Data block "+i+" is null!");
				} else {
					container.activate(data, 5);
					if(data.size() != CHKBlock.DATA_LENGTH) {
						System.err.println("Size of data block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
					} else {
						dataCount++;
					}
					System.err.println(data.toString()+" : "+data.size());
					container.deactivate(data, 5);
				}
			}
			if(dataCount == dataBlocks.length)
				System.out.println("Has all data blocks");
			else
				System.out.println("Does not have all data blocks: "+dataCount+" of "+dataBlocks.length);
		}
		if(checkBlocks != null) {
			System.err.println("Has check blocks");
			int dataCount = 0;
			for(int i=0;i<checkBlocks.length;i++) {
				Bucket data = checkBlocks[i];
				if(data == null) {
					System.err.println("Check block "+i+" is null!");
				} else {
					container.activate(data, 5);
					if(data.size() != CHKBlock.DATA_LENGTH) {
						System.err.println("Size of check block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
					} else {
						dataCount++;
					}
					System.err.println(data.toString()+" : "+data.size());
					container.deactivate(data, 5);
				}
			}
			if(dataCount == checkBlocks.length)
				System.out.println("Has all check blocks");
			else
				System.out.println("Does not have all check blocks: "+dataCount+" of "+checkBlocks.length);
		}
		if(dataBlockStatus != null) {
			System.err.println("Has data block status");
			int dataCount = 0;
			for(int i=0;i<dataBlockStatus.length;i++) {
				SplitfileBlock status = dataBlockStatus[i];
				Bucket data = status == null ? null : status.getData();
				if(data == null) {
					System.err.println("Data block "+i+" is null!");
				} else {
					container.activate(data, 5);
					if(data.size() != CHKBlock.DATA_LENGTH) {
						System.err.println("Size of data block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
					} else {
						dataCount++;
					}
					System.err.println(data.toString()+" : "+data.size());
					container.deactivate(data, 5);
				}
			}
			if(dataCount == dataBlockStatus.length)
				System.out.println("Has all data block statuses");
			else
				System.out.println("Does not have all data block statuses: "+dataCount+" of "+dataBlockStatus.length);
		}
		if(checkBlockStatus != null) {
			System.err.println("Has check block status");
			int dataCount = 0;
			for(int i=0;i<checkBlockStatus.length;i++) {
				SplitfileBlock status = checkBlockStatus[i];
				Bucket data = status == null ? null : status.getData();
				if(data == null) {
					System.err.println("Check block "+i+" is null!");
				} else {
					container.activate(data, 5);
					if(data.size() != CHKBlock.DATA_LENGTH) {
						System.err.println("Size of check block "+i+" is "+data.size()+" should be "+CHKBlock.DATA_LENGTH);
					} else {
						dataCount++;
					}
					System.err.println(data.toString()+" : "+data.size());
					container.deactivate(data, 5);
				}
			}
			if(dataCount == checkBlockStatus.length)
				System.out.println("Has all data block statuses");
			else
				System.out.println("Does not have all data block statuses: "+dataCount+" of "+checkBlockStatus.length);
		}
	}
}
