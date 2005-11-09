package freenet.client;

import java.util.Vector;

import freenet.keys.FreenetURI;
import freenet.keys.NodeCHK;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.compress.Compressor;

/**
 * Insert a splitfile.
 */
public class SplitInserter {

	final Bucket origData;
	final long dataLength;
	final ClientMetadata clientMetadata;
	final short compressionCodec;
	final short splitfileAlgorithm;
	final InserterContext ctx;
	SplitfileBlock[] origDataBlocks;
	
	public SplitInserter(Bucket data, ClientMetadata clientMetadata, Compressor compressor, short splitfileAlgorithm, InserterContext ctx) {
		this.origData = data;
		this.clientMetadata = clientMetadata;
		if(compressor == null)
			compressionCodec = -1;
		else
			compressionCodec = compressor.codecNumberForMetadata();
		this.splitfileAlgorithm = splitfileAlgorithm;
		this.ctx = ctx;
		this.dataLength = data.size();
	}

	InsertSegment encodingSegment;
	InsertSegment[] segments;
	final Vector unstartedSegments = new Vector();
	boolean allSegmentsFinished = false;
	
	/**
	 * Inserts the splitfile.
	 * @return The URI of the resulting file.
	 */
	public FreenetURI run() {
		// Create the splitfile
		int segmentSize = FECCodec.getCodecMaxSegmentSize(splitfileAlgorithm);
	
		splitIntoBlocks();
		
		splitIntoSegments(segmentSize);
		
		// Encode the last segment (which is always shortest)
		
		encodeSegment(segments.length-1);
		
		// Then start the insertion thread
		
		startInsertionThread();
		
		// Then encode the rest
		
		for(int i=0;i<segments.length-1;i++)
			encodeSegment(i);
		
		// Then wait for the insertion thread to finish
		
		return waitForCompletion();
	}

	private void splitIntoBlocks() {
		Bucket[] dataBuckets = BucketTools.split(origData, NodeCHK.BLOCK_SIZE);
		origDataBlocks = new SplitfileBlock[dataBuckets.length];
		for(int i=0;i<origDataBlocks.length;i++) {
			origDataBlocks[i] = new BucketWrapper(dataBuckets[i], i);
		}
	}

	/**
	 * Create the metadata document. Insert it. Return its URI.
	 */
	private FreenetURI finalStatus() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Group the blocks into segments.
	 */
	private void splitIntoSegments(int segmentSize) {
		int dataBlocks = origDataBlocks.length;

		// First split the data up
		if(dataBlocks < segmentSize || segmentSize == -1) {
			// Single segment
			InsertSegment onlySeg = new InsertSegment(splitfileAlgorithm, origDataBlocks);
			unstartedSegments.add(new InsertSegment[] { onlySeg });
		} else {
			int j = 0;
			for(int i=segmentSize;;i+=segmentSize) {
				if(i > dataBlocks) i = dataBlocks;
				Bucket[] seg = new Bucket[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, i-j);
				unstartedSegments.add(seg);
				j = i;
				if(i == dataBlocks) break;
			}
		}
		segments = (InsertSegment[]) unstartedSegments.toArray(new InsertSegment[unstartedSegments.size()]);
	}

	public static class BucketWrapper implements SplitfileBlock {

		Bucket data;
		int number;
		
		public BucketWrapper(Bucket data, int number) {
			this.data = data;
			this.number = number;
		}

		public int getNumber() {
			return number;
		}

		public boolean hasData() {
			return data != null;
		}

		public Bucket getData() {
			return data;
		}

		public void setData(Bucket data) {
			this.data = data;
		}

	}

}
