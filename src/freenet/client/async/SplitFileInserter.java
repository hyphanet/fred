package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.async.SingleFileInserter.SplitHandler;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.compress.Compressor;

public class SplitFileInserter implements ClientPutState {
	
	final BaseClientPutter parent;
	final InserterContext ctx;
	final PutCompletionCallback cb;
	final long dataLength;
	final short compressionCodec;
	final short splitfileAlgorithm;
	final int segmentSize;
	final int checkSegmentSize;
	final SplitFileInserterSegment[] segments;
	final boolean getCHKOnly;
	final int countCheckBlocks;
	final int countDataBlocks;
	private boolean haveSentMetadata;
	final ClientMetadata cm;
	final boolean isMetadata;
	private boolean finished;

	public SplitFileInserter(BaseClientPutter put, PutCompletionCallback cb, Bucket data, Compressor bestCodec, ClientMetadata clientMetadata, InserterContext ctx, boolean getCHKOnly, boolean isMetadata) throws InserterException {
		this.parent = put;
		this.finished = false;
		this.isMetadata = isMetadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		Bucket[] dataBuckets;
		try {
			dataBuckets = BucketTools.split(data, ClientCHKBlock.DATA_LENGTH, ctx.bf);
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR, e, null);
		}
		countDataBlocks = dataBuckets.length;
		// Encoding is done by segments
		if(bestCodec == null)
			compressionCodec = -1;
		else
			compressionCodec = bestCodec.codecNumberForMetadata();
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		this.dataLength = data.size();
		segmentSize = ctx.splitfileSegmentDataBlocks;
		checkSegmentSize = splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT ? 0 : ctx.splitfileSegmentCheckBlocks;
		
		// Create segments
		segments = splitIntoSegments(segmentSize, dataBuckets);
		int count = 0;
		for(int i=0;i<segments.length;i++)
			count += segments[i].countCheckBlocks();
		countCheckBlocks = count;
	}

	/**
	 * Group the blocks into segments.
	 */
	private SplitFileInserterSegment[] splitIntoSegments(int segmentSize, Bucket[] origDataBlocks) {
		int dataBlocks = origDataBlocks.length;

		Vector segs = new Vector();
		
		// First split the data up
		if(dataBlocks < segmentSize || segmentSize == -1) {
			// Single segment
			FECCodec codec = FECCodec.getCodec(splitfileAlgorithm, origDataBlocks.length);
			SplitFileInserterSegment onlySeg = new SplitFileInserterSegment(this, codec, origDataBlocks, ctx, getCHKOnly, 0);
			segs.add(onlySeg);
		} else {
			int j = 0;
			int segNo = 0;
			for(int i=segmentSize;;i+=segmentSize) {
				if(i > dataBlocks) i = dataBlocks;
				Bucket[] seg = new Bucket[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, i-j);
				j = i;
				for(int x=0;x<seg.length;x++)
					if(seg[x] == null) throw new NullPointerException("In splitIntoSegs: "+x+" is null of "+seg.length+" of "+segNo);
				FECCodec codec = FECCodec.getCodec(splitfileAlgorithm, seg.length);
				SplitFileInserterSegment s = new SplitFileInserterSegment(this, codec, seg, ctx, getCHKOnly, segNo);
				segs.add(s);
				
				if(i == dataBlocks) break;
				segNo++;
			}
		}
		parent.notifyClients();
		return (SplitFileInserterSegment[]) segs.toArray(new SplitFileInserterSegment[segs.size()]);
	}
	
	public void start() throws InserterException {
		for(int i=0;i<segments.length;i++)
			segments[i].start();
	}

	public void encodedSegment(SplitFileInserterSegment segment) {
		Logger.minor(this, "Encoded segment "+segment.segNo+" of "+this);
	}
	
	public void segmentHasURIs(SplitFileInserterSegment segment) {
		if(haveSentMetadata) {
			Logger.error(this, "WTF? Already sent metadata");
			return;
		}
		
		boolean allHaveURIs = true;
		synchronized(this) {
			for(int i=0;i<segments.length;i++) {
				if(!segments[i].isEncoded())
					allHaveURIs = false;
			}
		}
		
		if(allHaveURIs) {
			Logger.minor(this, "Have URIs from all segments");
			boolean missingURIs;
			Metadata m = null;
			synchronized(this) {
				// Create metadata
				FreenetURI[] dataURIs = getDataURIs();
				FreenetURI[] checkURIs = getCheckURIs();
				
				Logger.minor(this, "Data URIs: "+dataURIs.length+", check URIs: "+checkURIs.length);
				
				missingURIs = anyNulls(dataURIs) || anyNulls(checkURIs);
				
				if(!missingURIs) {
					// Create Metadata
					m = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, cm, dataLength, compressionCodec, isMetadata);
				}
				haveSentMetadata = true;
			}
			if(missingURIs) {
				Logger.minor(this, "Missing URIs");
				// Error
				fail(new InserterException(InserterException.INTERNAL_ERROR, "Missing URIs after encoding", null));
				return;
			} else
				cb.onMetadata(m, this);
		}

	}
	
	private void fail(InserterException e) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		cb.onFailure(e, this);
	}

	// FIXME move this to somewhere
	private static boolean anyNulls(Object[] array) {
		for(int i=0;i<array.length;i++)
			if(array[i] == null) return true;
		return false;
	}

	private FreenetURI[] getCheckURIs() {
		// Copy check blocks from each segment into a FreenetURI[].
		FreenetURI[] uris = new FreenetURI[countCheckBlocks];
		int x = 0;
		for(int i=0;i<segments.length;i++) {
			FreenetURI[] segURIs = segments[i].getCheckURIs();
			if(x + segURIs.length > countCheckBlocks) 
				throw new IllegalStateException("x="+x+", segURIs="+segURIs.length+", countCheckBlocks="+countCheckBlocks);
			System.arraycopy(segURIs, 0, uris, x, segURIs.length);
			x += segURIs.length;
		}

		if(uris.length != x)
			throw new IllegalStateException("Total is wrong");
		
		return uris;
	}

	private FreenetURI[] getDataURIs() {
		// Copy check blocks from each segment into a FreenetURI[].
		FreenetURI[] uris = new FreenetURI[countDataBlocks];
		int x = 0;
		for(int i=0;i<segments.length;i++) {
			FreenetURI[] segURIs = segments[i].getDataURIs();
			if(x + segURIs.length > countDataBlocks) 
				throw new IllegalStateException("x="+x+", segURIs="+segURIs.length+", countDataBlocks="+countDataBlocks);
			System.arraycopy(segURIs, 0, uris, x, segURIs.length);
			x += segURIs.length;
		}

		if(uris.length != x)
			throw new IllegalStateException("Total is wrong");
		
		return uris;
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void segmentFinished(SplitFileInserterSegment segment) {
		Logger.minor(this, "Segment finished: "+segment);
		boolean allGone = true;
		synchronized(this) {
			if(finished) return;
			for(int i=0;i<segments.length;i++)
				if(!segments[i].isFinished()) allGone = false;
			
			InserterException e = segment.getException();
			if(e != null && e.isFatal()) {
				cancel();
			} else {
				if(!allGone) return;
			}
			finished = true;
		}
		try {
		// Finished !!
		FailureCodeTracker tracker = new FailureCodeTracker(true);
		boolean allSucceeded = true;
		for(int i=0;i<segments.length;i++) {
			InserterException e = segments[i].getException();
			if(e == null) continue;
			allSucceeded = false;
			if(e.errorCodes != null)
				tracker.merge(e.errorCodes);
			tracker.inc(e.getMode());
		}
		if(allSucceeded)
			cb.onSuccess(this);
		else {
			InserterException e;
			if(tracker.isFatal(true))
				cb.onFailure(new InserterException(InserterException.FATAL_ERRORS_IN_BLOCKS, tracker, null), this);
			else
				cb.onFailure(new InserterException(InserterException.TOO_MANY_RETRIES_IN_BLOCKS, tracker, null), this);
		}
		} catch (Throwable t) {
			// We MUST tell the parent *something*!
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InserterException(InserterException.INTERNAL_ERROR), this);
		}
	}

	public void cancel() {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		for(int i=0;i<segments.length;i++)
			segments[i].cancel();
	}

}
