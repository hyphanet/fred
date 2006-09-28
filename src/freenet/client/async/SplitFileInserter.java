/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.Vector;

import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.compress.Compressor;
import freenet.support.io.Bucket;
import freenet.support.io.BucketTools;

public class SplitFileInserter implements ClientPutState {

	private static boolean logMINOR;
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
	private boolean fetchable;
	public final Object token;
	final boolean insertAsArchiveManifest;

	public SimpleFieldSet getProgressFieldset() {
		SimpleFieldSet fs = new SimpleFieldSet();
		// don't save basic infrastructure such as ctx and parent
		// only save details of the request
		fs.put("Type", "SplitFileInserter");
		fs.put("DataLength", Long.toString(dataLength));
		fs.put("CompressionCodec", Short.toString(compressionCodec));
		fs.put("SplitfileCodec", Short.toString(splitfileAlgorithm));
		fs.put("Finished", Boolean.toString(finished));
		fs.put("SegmentSize", Integer.toString(segmentSize));
		fs.put("CheckSegmentSize", Integer.toString(checkSegmentSize));
		SimpleFieldSet segs = new SimpleFieldSet();
		for(int i=0;i<segments.length;i++) {
			segs.put(Integer.toString(i), segments[i].getProgressFieldset());
		}
		segs.put("Count", Integer.toString(segments.length));
		fs.put("Segments", segs);
		return fs;
	}

	public SplitFileInserter(BaseClientPutter put, PutCompletionCallback cb, Bucket data, Compressor bestCodec, ClientMetadata clientMetadata, InserterContext ctx, boolean getCHKOnly, boolean isMetadata, Object token, boolean insertAsArchiveManifest, boolean freeData) throws InserterException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parent = put;
		this.insertAsArchiveManifest = insertAsArchiveManifest;
		this.token = token;
		this.finished = false;
		this.isMetadata = isMetadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		Bucket[] dataBuckets;
		try {
			dataBuckets = BucketTools.split(data, CHKBlock.DATA_LENGTH, ctx.persistentBucketFactory);
		} catch (IOException e) {
			throw new InserterException(InserterException.BUCKET_ERROR, e, null);
		}
		if(freeData) data.free();
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

	public SplitFileInserter(BaseClientPutter parent, PutCompletionCallback cb, ClientMetadata clientMetadata, InserterContext ctx, boolean getCHKOnly, boolean metadata, Object token, boolean insertAsArchiveManifest, SimpleFieldSet fs) throws ResumeException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parent = parent;
		this.insertAsArchiveManifest = insertAsArchiveManifest;
		this.token = token;
		this.finished = false;
		this.isMetadata = metadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		// Don't read finished, wait for the segmentFinished()'s.
		String length = fs.get("DataLength");
		if(length == null) throw new ResumeException("No DataLength");
		try {
			dataLength = Long.parseLong(length);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt DataLength: "+e+" : "+length);
		}
		String tmp = fs.get("SegmentSize");
		if(length == null) throw new ResumeException("No SegmentSize");
		try {
			segmentSize = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt SegmentSize: "+e+" : "+length);
		}
		tmp = fs.get("CheckSegmentSize");
		if(length == null) throw new ResumeException("No CheckSegmentSize");
		try {
			checkSegmentSize = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt CheckSegmentSize: "+e+" : "+length);
		}
		String ccodec = fs.get("CompressionCodec");
		if(ccodec == null) throw new ResumeException("No compression codec");
		try {
			compressionCodec = Short.parseShort(ccodec);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt CompressionCodec: "+e+" : "+ccodec);
		}
		String scodec = fs.get("SplitfileCodec");
		if(scodec == null) throw new ResumeException("No splitfile codec");
		try {
			// FIXME remove soon, backwards compat hack!
			short t = Short.parseShort(scodec);
			if(t == -1)
				t = 1;
			splitfileAlgorithm = t;
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt SplitfileCodec: "+e+" : "+scodec);
		}
		SimpleFieldSet segFS = fs.subset("Segments");
		if(segFS == null) throw new ResumeException("No segments");
		String segc = segFS.get("Count");
		if(segc == null) throw new ResumeException("No segment count");
		int segmentCount;
		try {
			segmentCount = Integer.parseInt(segc);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt segment count: "+e+" : "+segc);
		}
		segments = new SplitFileInserterSegment[segmentCount];
		
		int dataBlocks = 0;
		int checkBlocks = 0;
		
		for(int i=0;i<segments.length;i++) {
			String index = Integer.toString(i);
			SimpleFieldSet segment = segFS.subset(index);
			segFS.removeSubset(index);
			if(segment == null) throw new ResumeException("No segment "+i);
			segments[i] = new SplitFileInserterSegment(this, segment, splitfileAlgorithm, ctx, getCHKOnly, i);
			dataBlocks += segments[i].countDataBlocks();
			checkBlocks += segments[i].countCheckBlocks();
		}
		
		this.countDataBlocks = dataBlocks;
		this.countCheckBlocks = checkBlocks;
	}

	/**
	 * Group the blocks into segments.
	 */
	private SplitFileInserterSegment[] splitIntoSegments(int segmentSize, Bucket[] origDataBlocks) {
		int dataBlocks = origDataBlocks.length;

		Vector segs = new Vector();
		
		// First split the data up
		if((dataBlocks < segmentSize) || (segmentSize == -1)) {
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
		
		if(countDataBlocks > 32)
			parent.onMajorProgress();
		parent.notifyClients();
		
	}

	public void encodedSegment(SplitFileInserterSegment segment) {
		if(logMINOR) Logger.minor(this, "Encoded segment "+segment.segNo+" of "+this);
		synchronized(this) {
			for(int i=0;i<segments.length;i++) {
				if((segments[i] == null) || !segments[i].isEncoded())
					return;
			}
		}
		cb.onBlockSetFinished(this);
		if(countDataBlocks > 32)
			parent.onMajorProgress();
	}
	
	public void segmentHasURIs(SplitFileInserterSegment segment) {
		if(logMINOR) Logger.minor(this, "Segment has URIs: "+segment);
		synchronized(this) {
			if(haveSentMetadata) {
				return;
			}
			
			for(int i=0;i<segments.length;i++) {
				if(!segments[i].hasURIs()) {
					if(logMINOR) Logger.minor(this, "Segment does not have URIs: "+segments[i]);
					return;
				}
			}
		}
		
		if(logMINOR) Logger.minor(this, "Have URIs from all segments");
		encodeMetadata();
	}
	
	private void encodeMetadata() {
		boolean missingURIs;
		Metadata m = null;
		synchronized(this) {
			// Create metadata
			FreenetURI[] dataURIs = getDataURIs();
			FreenetURI[] checkURIs = getCheckURIs();
			
			if(logMINOR) Logger.minor(this, "Data URIs: "+dataURIs.length+", check URIs: "+checkURIs.length);
			
			missingURIs = anyNulls(dataURIs) || anyNulls(checkURIs);
			
			if(!missingURIs) {
				// Create Metadata
				m = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, cm, dataLength, compressionCodec, isMetadata, insertAsArchiveManifest);
			}
			haveSentMetadata = true;
		}
		if(missingURIs) {
			if(logMINOR) Logger.minor(this, "Missing URIs");
			// Error
			fail(new InserterException(InserterException.INTERNAL_ERROR, "Missing URIs after encoding", null));
			return;
		} else
			cb.onMetadata(m, this);
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
		if(logMINOR) Logger.minor(this, "Segment finished: "+segment, new Exception("debug"));
		boolean allGone = true;
		if(countDataBlocks > 32)
			parent.onMajorProgress();
		synchronized(this) {
			if(finished) {
				if(logMINOR) Logger.minor(this, "Finished already");
				return;
			}
			for(int i=0;i<segments.length;i++) {
				if(!segments[i].isFinished()) {
					if(logMINOR) Logger.minor(this, "Segment not finished: "+i+": "+segments[i]);
					allGone = false;
					break;
				}
			}
			
			InserterException e = segment.getException();
			if((e != null) && e.isFatal()) {
				cancel();
			} else {
				if(!allGone) return;
			}
			finished = true;
		}
		onAllFinished();
	}
	
	public void segmentFetchable(SplitFileInserterSegment segment) {
		if(logMINOR) Logger.minor(this, "Segment fetchable: "+segment);
		synchronized(this) {
			if(finished) return;
			if(fetchable) return;
			for(int i=0;i<segments.length;i++) {
				if(!segments[i].isFetchable()) {
					if(logMINOR) Logger.minor(this, "Segment not fetchable: "+i+": "+segments[i]);
					return;
				}
			}
			fetchable = true;
		}
		cb.onFetchable(this);
	}

	private void onAllFinished() {
		if(logMINOR) Logger.minor(this, "All finished");
		try {
			// Finished !!
			FailureCodeTracker tracker = new FailureCodeTracker(true);
			boolean allSucceeded = true;
			for(int i=0;i<segments.length;i++) {
				InserterException e = segments[i].getException();
				if(e == null) continue;
				if(logMINOR) Logger.minor(this, "Failure on segment "+i+" : "+segments[i]+" : "+e, e);
				allSucceeded = false;
				if(e.errorCodes != null)
					tracker.merge(e.errorCodes);
				tracker.inc(e.getMode());
			}
			if(allSucceeded)
				cb.onSuccess(this);
			else {
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

	public void schedule() throws InserterException {
		start();
	}

	public Object getToken() {
		return token;
	}

	public long getLength() {
		return dataLength;
	}

}
