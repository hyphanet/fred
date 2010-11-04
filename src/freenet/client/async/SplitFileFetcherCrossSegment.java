package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.FECCallback;
import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FECQueue;
import freenet.client.FetchException;
import freenet.client.SplitfileBlock;
import freenet.keys.CHKBlock;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class SplitFileFetcherCrossSegment implements FECCallback {

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	// The blocks are all drawn from ordinary segments.
	private final SplitFileFetcherSegment[] segments;
	private final SplitFileFetcher splitFetcher;
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
	
	public SplitFileFetcherCrossSegment(boolean persistent, int blocksPerSegment, int crossCheckBlocks, ClientRequester parent, SplitFileFetcher fetcher, short splitfileType) {
		this.persistent = persistent;
		this.dataBlocks = blocksPerSegment;
		this.crossCheckBlocks = crossCheckBlocks;
		this.parent = parent;
		this.splitfileType = splitfileType;
		int totalBlocks = dataBlocks + crossCheckBlocks;
		segments = new SplitFileFetcherSegment[totalBlocks];
		blockNumbers = new int[totalBlocks];
		blocksFound = new boolean[totalBlocks];
		this.splitFetcher = fetcher;
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
			if(shouldRemove || finishedEncoding || startedDecoding || startedEncoding) return;
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
			if(logMINOR) Logger.minor(this, "Finished as nothing to encode/decode in onFetched on "+this);
			if(bye)
				onFinished(container, context);
			else if(persistent)
				container.store(this);
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
				if(data == null) {
					Logger.error(this, "Cannot decode/encode: Found block "+i+" : "+blockNumbers[i]+" of "+segments[i]+" but is gone now!", new Exception("error"));
					data = seg.getBlockBucket(blockNumbers[i], container);
					if(data == null) {
						if(seg.isFinished(container))
							Logger.error(this, "SEGMENT IS FINISHED ALREADY when trying to decode/encode splitfile block: "+segments[i]);
						else if(seg.isFinishing(container))
							Logger.error(this, "SEGMENT IS FINISHING ALREADY when trying to decode/encode splitfile block: "+segments[i]);
						if(seg.hasBlockWrapper(blockNumbers[i]))
							Logger.error(this, "SEGMENT HAS BLOCK WRAPPER BUT NOT BUCKET: "+seg);
						Logger.error(this, "Cannot decode/encode: Found block "+i+" : "+blockNumbers[i]+" of "+segments[i]+" but is gone now!", new Exception("error"));
						SplitFileFetcher fetcher = getFetcher(container);
						if(fetcher != null) {
							container.activate(fetcher, 1);
							fetcher.onFailed(new FetchException(FetchException.INTERNAL_ERROR, "Cannot decode/encode cross blocks: lost a block"), container, context);
						}
						return false;
					} else {
						Logger.error(this, "Synchronization bug: got the bucket the second time?!");
					}
				}
				wrapper.assertSetData(data);
				if(persistent) container.activate(data, Integer.MAX_VALUE);
				if(!active) container.deactivate(seg, 1);
				if(persistent) wrapper.storeTo(container);
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
				if(startedDecoding) {
					if(logMINOR) Logger.minor(this, "Not starting decoding, already started, on "+this);
					return true;
				}
				startedDecoding = true;
				if(persistent) container.store(this);
				if(logMINOR) Logger.minor(this, "Starting decoding on "+this);
			} else {
				if(startedEncoding) {
					if(logMINOR) Logger.minor(this, "Not starting encoding, already started, on "+this);
					return true;
				}
				startedEncoding = true;
				if(persistent) container.store(this);
				if(logMINOR) Logger.minor(this, "Starting encoding on "+this);
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
		if(logMINOR) Logger.minor(this, "Decoded segment on "+this);
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
					Logger.normal(this, "Cross-segment decoded a block.");
			}
			if(!active) container.deactivate(seg, 1);
			dataBlocks[i].clearData();
			if(persistent) container.delete(dataBlocks[i]);
		}
		boolean bye = false;
		synchronized(this) {
			if(shouldRemove) {
				// Skip the encode.
				bye = true;
				finishedEncoding = true;
				if(logMINOR) Logger.minor(this, "Finished as cancelled in decoded segment on "+this);
			}
		}
		if(bye) {
			onFinished(container, context);
		} else {
			// Try an encode now.
			if(!decodeOrEncode(null, container, context)) {
				// Didn't schedule a job. So it doesn't need to encode and hasn't already started encoding.
				synchronized(this) {
					bye = shouldRemove;
					finishedEncoding = true;
				}
				if(logMINOR) Logger.minor(this, "Finished as nothing to encode/decode in decoded segment on "+this);
				if(bye)
					onFinished(container, context);
				else if(persistent)
					container.store(this);
			}
		}
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks) {
		if(logMINOR) Logger.minor(this, "Encoded segment on "+this);
		for(int i=0;i<checkBlocks.length;i++) {
			Bucket data = checkBlocks[i].getData();
			boolean found;
			int num = this.dataBlocks+i;
			synchronized(this) {
				found = blocksFound[num];
				if(data == null) {
					Logger.error(this, "Check block "+i+" is null in onEncodedSegment on "+this+" - shouldRemove = "+shouldRemove);
					continue;
				}
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
				if(seg.onSuccess(data, blockNumbers[num], null, container, context, null))
					Logger.normal(this, "Cross-segment encoded a block.");
			}
			if(!active) container.deactivate(seg, 1);
			checkBlocks[i].clearData();
			if(persistent) container.delete(checkBlocks[i]);
		}
		// All done.
		boolean bye = false;
		synchronized(this) {
			finishedEncoding = true;
			bye = shouldRemove;
		}
		if(logMINOR) Logger.minor(this, "Finished encoding on "+this);
		if(bye) onFinished(container, context);
		else if(persistent)
			container.store(this);
	}

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finishedEncoding) {
				Logger.error(this, "Failed but already finished, ignoring on "+this, t);
				return;
			}
		}
		Logger.error(this, "Encode or decode failed for cross segment: "+this, t);
		SplitFileFetcher fetcher = getFetcher(container);
		if(persistent) container.activate(fetcher, 1);
		fetcher.onFailed(new FetchException(FetchException.INTERNAL_ERROR, t), container, context);
	}

	public void addDataBlock(SplitFileFetcherSegment seg, int blockNum) {
		segments[counter] = seg;
		blockNumbers[counter] = blockNum;
		counter++;
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}
	
	public void onFinished(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Finished on "+this);
		assert(finishedEncoding); // Caller must set.
		SplitFileFetcher fetcher = getFetcher(container);
		if(fetcher == null) return;
		boolean active = container.ext().isActive(fetcher);
		if(persistent && !active) container.activate(fetcher, 1);
		if(!fetcher.onFinishedCrossSegment(container, context, this)) {
			if(persistent) container.store(this);
		}
		if(persistent && !active) container.deactivate(fetcher, 1);
	}

	/** Notify that the splitfile has finished. We can skip encodes and decodes if possible. */
	public void preRemove(ObjectContainer container, ClientContext context) {
		synchronized(this) {
			shouldRemove = true;
			if(!(startedDecoding || startedEncoding)) {
				finishedEncoding = true;
				startedDecoding = true;
				startedEncoding = true;
			}
		}
		container.store(this);
	}

	/** Final removal, after all direct segments have been removed. */
	public void removeFrom(ObjectContainer container, ClientContext context) {
		container.delete(this);
	}

	private SplitFileFetcher getFetcher(ObjectContainer container) {
		if(splitFetcher != null) return splitFetcher;
		// FIXME horrible back compat code
		for(int i=0;i<segments.length;i++) {
			if(segments[i] == null) continue;
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(segments[i]);
				container.activate(segments[i], 1);
			}
			SplitFileFetcher fetcher = segments[i].parentFetcher;
			if(!active)
				container.deactivate(segments[i], 1);
			return fetcher;
		}
		return null;
	}

	public boolean isFinished() {
		return finishedEncoding;
	}

	

}
