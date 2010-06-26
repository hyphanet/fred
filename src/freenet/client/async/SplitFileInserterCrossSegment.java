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

public class SplitFileInserterCrossSegment implements FECCallback {
	
	private final boolean persistent;
	private final int dataBlocks;
	private final int crossCheckBlocks;
	private final SplitFileInserterSegment[] segments;
	private final int[] blockNumbers;
	private final ClientRequester parent;
	
	final short splitfileType;
	
	private transient int counter;
	
	private transient FECCodec codec;
	
	SplitFileInserterCrossSegment(boolean persistent, int dataBlocks, int crossCheckBlocks, ClientRequester parent, short splitfileType) {
		this.persistent = persistent;
		this.dataBlocks = dataBlocks;
		this.crossCheckBlocks = crossCheckBlocks;
		int totalBlocks = dataBlocks + crossCheckBlocks;
		this.segments = new SplitFileInserterSegment[totalBlocks];
		this.blockNumbers = new int[totalBlocks];
		this.parent = parent;
		this.splitfileType = splitfileType;
	}
	
	public void addDataBlock(SplitFileInserterSegment seg, int blockNum) {
		System.out.println("Allocated "+counter+" blocks: block "+blockNum+" on segment "+seg.segNo+" to "+this);
		segments[counter] = seg;
		blockNumbers[counter] = blockNum;
		counter++;
	}

	/** Start the encode */
	public void start(ObjectContainer container, ClientContext context) {
		System.out.println("Scheduling encode for cross segment "+this);
		// Schedule encode job.
		SplitfileBlock[] decodeData = new SplitfileBlock[dataBlocks];
		SplitfileBlock[] decodeCheck = new SplitfileBlock[segments.length - dataBlocks];
		for(int i=0;i<decodeData.length;i++) {
			MinimalSplitfileBlock wrapper = new MinimalSplitfileBlock(i);
			SplitFileInserterSegment seg = segments[i];
			boolean active = true;
			if(persistent) {
				active = container.ext().isActive(seg);
				if(!active) container.activate(seg, 1);
			}
			Bucket data = seg.getBucket(blockNumbers[i]);
			wrapper.data = data;
			if(persistent) container.activate(data, Integer.MAX_VALUE);
			if(!active) container.deactivate(seg, 1);
			decodeData[i] = wrapper;
		}
		for(int i=0;i<decodeCheck.length;i++) {
			decodeCheck[i] = new MinimalSplitfileBlock(i);
		}
		FECQueue queue = context.fecQueue;
		if(codec == null)
			codec = FECCodec.getCodec(splitfileType, dataBlocks, decodeCheck.length);
		FECJob job = new FECJob(codec, queue, decodeData, decodeCheck, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), this, false, getPriorityClass(container), persistent);
		codec.addToQueue(job, 
				queue, container);
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

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}

	public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks) {
		// Can't happen.
		throw new UnsupportedOperationException();
	}

	public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets, Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks) {
		for(int i=0;i<crossCheckBlocks;i++) {
			SplitFileInserterSegment seg = segments[i + this.dataBlocks];
			int blockNum = blockNumbers[i + this.dataBlocks];
			if(persistent) container.activate(seg, 1);
			seg.onEncodedCrossCheckBlock(blockNum, checkBlocks[i].getData(), container, context);
			if(persistent) container.deactivate(seg, 1);
			checkBlocks[i].setData(null);
			if(persistent) container.delete(checkBlocks[i]);
		}
		for(int i=0;i<dataBlocks.length;i++) {
			dataBlocks[i].setData(null);
			if(persistent) container.delete(dataBlocks[i]);
		}
		System.out.println("Completed encode for cross segment "+this);
	}

	public void onFailed(Throwable t, ObjectContainer container, ClientContext context) {
		Logger.error(this, "Encode or decode failed for cross segment: "+this);
	}

}
