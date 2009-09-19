/**
 * 
 */
package freenet.client;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
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
		this.dataBlocks = dataBlocks;
		this.checkBlocks = checkBlocks;
		this.dataBlockStatus = null;
		this.checkBlockStatus = null;
		this.blockLength = blockLength;
		this.bucketFactory = bucketFactory;
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
	
	public void activateForExecution(ObjectContainer container) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Activating FECJob...");
		if(dataBlockStatus != null && logMINOR) {
			for(int i=0;i<dataBlockStatus.length;i++)
				Logger.minor(this, "Data block status "+i+": "+dataBlockStatus[i]+" (before activation)");
		}
		container.activate(this, 2);
		if(dataBlockStatus != null) {
			for(int i=0;i<dataBlockStatus.length;i++)
				container.activate(dataBlockStatus[i], 2);
		}
		if(dataBlockStatus != null && logMINOR) {
			for(int i=0;i<dataBlockStatus.length;i++)
				Logger.minor(this, "Data block status "+i+": "+dataBlockStatus[i]+" (after activation)");
		}
		if(checkBlockStatus != null) {
			for(int i=0;i<checkBlockStatus.length;i++)
				container.activate(checkBlockStatus[i], 2);
		}
		if(dataBlocks != null) {
			for(int i=0;i<dataBlocks.length;i++) {
				Logger.minor(this, "Data bucket "+i+": "+dataBlocks[i]+" (before activation)");
				container.activate(dataBlocks[i], 1);
				Logger.minor(this, "Data bucket "+i+": "+dataBlocks[i]+" (after activation)");
			}
		}
		if(checkBlocks != null) {
			for(int i=0;i<checkBlocks.length;i++)
				container.activate(checkBlocks[i], 1);
		}
		
	}

	public void deactivate(ObjectContainer container) {
		if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Deactivating FECJob...");
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
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
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
}
