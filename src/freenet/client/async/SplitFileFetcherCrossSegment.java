package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FECQueue;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.support.Logger;
import freenet.support.api.Bucket;

public class SplitFileFetcherCrossSegment implements FECCallback {

	// The blocks are all drawn from ordinary segments.
	private final SplitFileFetcherSegment[] segments;
	private final int[] blockNumbers;
	private final boolean[] blocksFound;
	/** Count of data blocks i.e. blocks other than cross-check blocks. 
	 * Decode when this many blocks are found. 
	 * Note that all blocks past this number in the above arrays are check blocks. 
	 * Typically there are only 3 such blocks. */
	final int dataBlocks;
	final int crossCheckBlocks;
	final boolean persistent;
	final short splitfileType;
	final ClientRequester parent;
	private boolean finishedEncoding;
	private boolean startedDecoding;
	private boolean startedEncoding;
	private boolean shouldRemove;
	
	private transient int counter;
	
	private transient FECCodec codec;
	
	public SplitFileFetcherCrossSegment(boolean persistent, int blocksPerSegment, int crossCheckBlocks, ClientRequester parent, short splitfileType) {
		this.persistent = persistent;
		this.dataBlocks = blocksPerSegment;
		this.crossCheckBlocks = crossCheckBlocks;
		this.parent = parent;
		this.splitfileType = splitfileType;
		int totalBlocks = dataBlocks + crossCheckBlocks;
		segments = new SplitFileFetcherSegment[totalBlocks];
		blockNumbers = new int[totalBlocks];
		blocksFound = new boolean[totalBlocks];
	}

	public void onFetched(SplitFileFetcherSegment segment, int blockNo, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			boolean found = false;
			int totalFound = 0;
			for(int i=0;i<segments.length;i++) {
				if(segments[i] == segment && blockNumbers[i] == blockNo) {
					found = true;
					if(blocksFound[i]) {
						// Already handled, don't loop.
						return;
					}
					blocksFound[i] = true;
				}
				if(blocksFound[i]) totalFound++;
			}
			if(persistent) container.store(this);
			if(!found) {
				Logger.error(this, "Block "+blockNo+" on "+segment+" not wanted by "+this);
				return;
			}
			if(totalFound < dataBlocks) {
				Logger.normal(this, "Not decoding "+this+" : found "+totalFound+" blocks of "+dataBlocks+" (total "+segments.length+")");
				return;
			}
		}
		if(!decodeOrEncode(segment, container, context)) {
			// Don't need to encode or decode.
			boolean bye;
			synchronized(this) {
				bye = shouldRemove;
				finishedEncoding = true;
			}
			if(persistent) {
				if(bye)
					removeFrom(container, context);
				else
					container.store(this);
			}
			
		}
	}

	/**
	 * 
	 * @param segment
	 * @param container
	 * @param context
	 * @return True unless we didn't schedule a job and are not already running one.
	 */
	private boolean decodeOrEncode(SplitFileFetcherSegment segment, ObjectContainer container, ClientContext context) {
		// Schedule decode or encode job depending on what is needed, decode always first.
		boolean needsDecode = false;
		boolean needsEncode = false;
		SplitfileBlock[] decodeData = new SplitfileBlock[dataBlocks];
		SplitfileBlock[] decodeCheck = new SplitfileBlock[segments.length - dataBlocks];
		for(int i=0;i<segments.length;i++) {
			MinimalSplitfileBlock wrapper = new MinimalSplitfileBlock(i);
			boolean found;
			synchronized(this) {
				found = blocksFound[i];
			}
			if(found) {
				SplitFileFetcherSegment seg = segments[i];
				boolean active = true;
				if(seg != segment && persistent) {
					active = container.ext().isActive(seg);
					if(!active) container.activate(seg, 1);
				}
				Bucket data = seg.getBlockBucket(blockNumbers[i], container);
				wrapper.data = data;
				if(persistent) container.activate(data, Integer.MAX_VALUE);
				if(!active) container.deactivate(seg, 1);
			} else {
				// wrapper has no data, we want to decode it.
				if(i < dataBlocks) needsDecode = true;
				else needsEncode = true;
			}
			if(i < dataBlocks)
				decodeData[i] = wrapper;
			else
				decodeCheck[i-dataBlocks] = wrapper;
		}
		if(!(needsDecode || needsEncode)) {
			return false;
		}
		synchronized(this) {
			if(needsDecode) {
				if(startedDecoding) return true;
				startedDecoding = true;
				if(persistent) container.store(this);
			} else {
				if(startedEncoding) return true;
				startedEncoding = true;
				if(persistent) container.store(this);
			}
		}
		FECQueue queue = context.fecQueue;
		if(codec == null)
			codec = FECCodec.getCodec(splitfileType, dataBlocks, decodeCheck.length);
		FECJob job = new FECJob(codec, queue, decodeData, decodeCheck, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), this, needsDecode, getPriorityClass(container), persistent);
		codec.addToQueue(job, 
				queue, container);
		return true;
	}

	private short getPriorityClass(ObjectContainer container) {
		boolean parentActive = true;
		if(persistent) {
			parentActive = container.ext().isActive(parent);
			if(!parentActive) container.activate(parent, 1);
		}
		short ret = parent.getPriorityClass();
		if(!parentActive) container.deactivate(parent, 1);
		return ret;
	}

	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks) {
		for(int i=0;i<dataBlocks.length;i++) {
			Bucket data = dataBlocks[i].getData();
			boolean found;
			synchronized(this) {
				found = blocksFound[i];
			}
			SplitFileFetcherSegment seg = segments[i];
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(seg);
				if(!active) container.activate(seg, 1);
			}
			if(found) {
				// Was it found independantly?
				// If so, we can just free it.
				// Or maybe it was passed in, in which case we should do nothing.
				// However, we need to be careful to avoid the db4o same ID bug.
				// If that happens, we could have two buckets with are != but have the same ID, and freeing one will free the other's underlying resources. That would be bad!
				Bucket segData = seg.getBlockBucket(blockNumbers[i], container);
				if(segData != data) {
					// Either we have a db4o bug, or it was downloaded independantly.
					if(persistent && container.ext().getID(segData) == container.ext().getID(data)) {
						Logger.error(this, "SAME ID BUG FOUND IN CROSS SEGMENT DECODE: "+segData+" has same ID as "+data);
						// Do nothing.
					} else {
						// Downloaded independantly (or race condition). Ditch the new block.
						data.free();
						if(persistent) data.removeFrom(container);
					}
				}
			} else {
				// Yay we decoded a block. Tell the segment.
				blocksFound[i] = true;
				if(seg.onSuccess(data, blockNumbers[i], null, container, context, null))
					System.out.println("Cross-segment decoded a block.");
			}
			if(!active) container.deactivate(seg, 1);
			dataBlocks[i].setData(null);
			if(persistent) container.delete(dataBlocks[i]);
		}
		boolean bye = false;
		synchronized(this) {
			if(shouldRemove) {
				// Skip the encode.
				bye = true;
				finishedEncoding = true;
			}
		}
		if(bye) {
			if(persistent) removeFrom(container, context);
		} else {
			// Try an encode now.
			if(!decodeOrEncode(null, container, context)) {
				// Didn't schedule a job. So it doesn't need to encode and hasn't already started encoding.
				synchronized(this) {
					bye = shouldRemove;
					finishedEncoding = true;
				}
				if(persistent) {
					if(bye)
						removeFrom(container, context);
					else
						container.store(this);
				}
			}
		}
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks) {
		for(int i=0;i<checkBlocks.length;i++) {
			Bucket data = checkBlocks[i].getData();
			boolean found;
			int num = this.dataBlocks+i;
			synchronized(this) {
				found = blocksFound[num];
			}
			SplitFileFetcherSegment seg = segments[num];
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(seg);
				if(!active) container.activate(seg, 1);
			}
			if(found) {
				// Was it found independantly?
				// If so, we can just free it.
				// Or maybe it was passed in, in which case we should do nothing.
				// However, we need to be careful to avoid the db4o same ID bug.
				// If that happens, we could have two buckets with are != but have the same ID, and freeing one will free the other's underlying resources. That would be bad!
				Bucket segData = seg.getBlockBucket(blockNumbers[i], container);
				if(segData != data) {
					// Either we have a db4o bug, or it was downloaded independantly.
					if(persistent && container.ext().getID(segData) == container.ext().getID(data)) {
						Logger.error(this, "SAME ID BUG FOUND IN CROSS SEGMENT DECODE: "+segData+" has same ID as "+data);
						// Do nothing.
					} else {
						// Downloaded independantly (or race condition). Ditch the new block.
						data.free();
						if(persistent) data.removeFrom(container);
					}
				}
			} else {
				// Yay we decoded a block. Tell the segment.
				if(seg.onSuccess(data, blockNumbers[i], null, container, context, null))
					System.out.println("Cross segment encoded a block.");
			}
			if(!active) container.deactivate(seg, 1);
			checkBlocks[i].setData(null);
			if(persistent) container.delete(checkBlocks[i]);
		}
		// All done.
		boolean bye = false;
		synchronized(this) {
			finishedEncoding = true;
			bye = shouldRemove;
		}
		if(persistent) {
			if(bye) removeFrom(container, context);
			else
				container.store(this);
		}
	}

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Encode or decode failed for cross segment: "+this, t);
	}

	public void addDataBlock(SplitFileFetcherSegment seg, int blockNum) {
		segments[counter] = seg;
		blockNumbers[counter] = blockNum;
		counter++;
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		boolean finished;
		synchronized(this) {
			shouldRemove = true;
			finished = finishedEncoding;
		}
		if(finished)
			container.delete(this);
		else
			container.store(this);
	}

	

}
