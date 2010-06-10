/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LoggerPriority;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;

public class SplitFileInserter implements ClientPutState {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LoggerPriority.MINOR, this);
				logDEBUG = Logger.shouldLog(LoggerPriority.DEBUG, this);
			}
		});
	}

	final BaseClientPutter parent;
	final InsertContext ctx;
	final PutCompletionCallback cb;
	final long dataLength;
	final COMPRESSOR_TYPE compressionCodec;
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
	private volatile boolean finished;
	private boolean fetchable;
	public final Object token;
	final ARCHIVE_TYPE archiveType;
	private boolean forceEncode;
	private final long decompressedLength;
	final boolean persistent;

	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.

	private final int hashCode;

	@Override
	public int hashCode() {
		return hashCode;
	}

	public SplitFileInserter(BaseClientPutter put, PutCompletionCallback cb, Bucket data, COMPRESSOR_TYPE bestCodec, long decompressedLength, ClientMetadata clientMetadata, InsertContext ctx, boolean getCHKOnly, boolean isMetadata, Object token, ARCHIVE_TYPE archiveType, boolean freeData, boolean persistent, ObjectContainer container, ClientContext context) throws InsertException {
		hashCode = super.hashCode();
		if(put == null) throw new NullPointerException();
		this.parent = put;
		this.archiveType = archiveType;
		this.compressionCodec = bestCodec;
		this.token = token;
		this.finished = false;
		this.isMetadata = isMetadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		this.decompressedLength = decompressedLength;
		this.dataLength = data.size();
		Bucket[] dataBuckets;
		context.jobRunner.setCommitThisTransaction();
		try {
			dataBuckets = BucketTools.split(data, CHKBlock.DATA_LENGTH, persistent ? context.persistentBucketFactory : context.tempBucketFactory, freeData, persistent, container);
				if(dataBuckets[dataBuckets.length-1].size() < CHKBlock.DATA_LENGTH) {
					Bucket oldData = dataBuckets[dataBuckets.length-1];
					dataBuckets[dataBuckets.length-1] = BucketTools.pad(oldData, CHKBlock.DATA_LENGTH, context.getBucketFactory(persistent), (int) oldData.size());
					if(persistent) dataBuckets[dataBuckets.length-1].storeTo(container);
					oldData.free();
					if(persistent) oldData.removeFrom(container);
				}
			if(logMINOR)
				Logger.minor(this, "Data size "+data.size()+" buckets "+dataBuckets.length);
		} catch (IOException e) {
			throw new InsertException(InsertException.BUCKET_ERROR, e, null);
		}
		countDataBlocks = dataBuckets.length;
		// Encoding is done by segments
		this.splitfileAlgorithm = ctx.splitfileAlgorithm;
		segmentSize = ctx.splitfileSegmentDataBlocks;
		checkSegmentSize = splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT ? 0 : ctx.splitfileSegmentCheckBlocks;

		this.persistent = persistent;
		if(persistent) {
			container.activate(parent, 1);
		}

		// Create segments
		segments = splitIntoSegments(segmentSize, dataBuckets, context.mainExecutor, container, context, persistent, put);
		if(persistent) {
			// Deactivate all buckets, and let dataBuckets be GC'ed
			for(int i=0;i<dataBuckets.length;i++) {
				// If we don't set them now, they will be set when the segment is set, which means they will be set deactivated, and cause NPEs.
				dataBuckets[i].storeTo(container);
				container.deactivate(dataBuckets[i], 1);
				if(dataBuckets.length > segmentSize) // Otherwise we are nulling out within the segment
					dataBuckets[i] = null;
			}
		}
		dataBuckets = null;
		int count = 0;
		for(int i=0;i<segments.length;i++)
			count += segments[i].countCheckBlocks();
		countCheckBlocks = count;
		// Save progress to disk, don't want to do all that again (probably includes compression in caller)
		parent.onMajorProgress(container);
		if(persistent) {
			for(int i=0;i<segments.length;i++) {
				container.store(segments[i]);
				container.deactivate(segments[i], 1);
			}
		}
	}

	public SplitFileInserter(BaseClientPutter parent, PutCompletionCallback cb, ClientMetadata clientMetadata, InsertContext ctx, boolean getCHKOnly, boolean metadata, Object token, ARCHIVE_TYPE archiveType, SimpleFieldSet fs, ObjectContainer container, ClientContext context) throws ResumeException {
		hashCode = super.hashCode();
		this.parent = parent;
		this.archiveType = archiveType;
		this.token = token;
		this.finished = false;
		this.isMetadata = metadata;
		this.cm = clientMetadata;
		this.getCHKOnly = getCHKOnly;
		this.cb = cb;
		this.ctx = ctx;
		this.persistent = parent.persistent();
		context.jobRunner.setCommitThisTransaction();
		// Don't read finished, wait for the segmentFinished()'s.
		String length = fs.get("DataLength");
		if(length == null) throw new ResumeException("No DataLength");
		try {
			dataLength = Long.parseLong(length);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt DataLength: "+e+" : "+length);
		}
		length = fs.get("DecompressedLength");
		long dl = 0; // back compat
		if(length != null) {
			try {
				dl = Long.parseLong(length);
			} catch (NumberFormatException e) {
				dl = -1;
			}
		}
		decompressedLength = dl;
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
		COMPRESSOR_TYPE compressor = null;
		if(ccodec != null) {
			try {
				compressor = COMPRESSOR_TYPE.valueOf(ccodec);
			} catch (Throwable t) {
				try {
					short codecNo = Short.parseShort(ccodec);
					compressor = COMPRESSOR_TYPE.getCompressorByMetadataID(codecNo);
				} catch (NumberFormatException nfe) {
					throw new ResumeException("Invalid compression codec: "+ccodec);
				}
			}
		}
		compressionCodec = compressor;
		String scodec = fs.get("SplitfileCodec");
		if(scodec == null) throw new ResumeException("No splitfile codec");
		try {
			splitfileAlgorithm = Short.parseShort(scodec);
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
			segments[i] = new SplitFileInserterSegment(this, persistent, parent, segment, splitfileAlgorithm, ctx, getCHKOnly, i, context, container);
			dataBlocks += segments[i].countDataBlocks();
			checkBlocks += segments[i].countCheckBlocks();
		}

		this.countDataBlocks = dataBlocks;
		this.countCheckBlocks = checkBlocks;
	}

	/**
	 * Group the blocks into segments.
	 */
	private SplitFileInserterSegment[] splitIntoSegments(int segmentSize, Bucket[] origDataBlocks, Executor executor, ObjectContainer container, ClientContext context, boolean persistent, BaseClientPutter putter) {
		int dataBlocks = origDataBlocks.length;

		ArrayList<SplitFileInserterSegment> segs = new ArrayList<SplitFileInserterSegment>();

		// First split the data up
		if((dataBlocks < segmentSize) || (segmentSize == -1)) {
			// Single segment
			SplitFileInserterSegment onlySeg = new SplitFileInserterSegment(this, persistent, putter, splitfileAlgorithm, FECCodec.getCheckBlocks(splitfileAlgorithm, origDataBlocks.length), origDataBlocks, ctx, getCHKOnly, 0, container);
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
				SplitFileInserterSegment s = new SplitFileInserterSegment(this, persistent, putter, splitfileAlgorithm, FECCodec.getCheckBlocks(splitfileAlgorithm, seg.length), seg, ctx, getCHKOnly, segNo, container);
				segs.add(s);

				if(i == dataBlocks) break;
				segNo++;
			}
		}
		if(persistent)
			container.activate(parent, 1);
		parent.notifyClients(container, context);
		return segs.toArray(new SplitFileInserterSegment[segs.size()]);
	}

	public void start(ObjectContainer container, final ClientContext context) throws InsertException {
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].start(container, context);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
		if(persistent)
			container.activate(parent, 1);

		if(countDataBlocks > 32)
			parent.onMajorProgress(container);
		parent.notifyClients(container, context);

	}

	public void encodedSegment(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Encoded segment "+segment.segNo+" of "+this);
		boolean ret = false;
		boolean encode;
		synchronized(this) {
			encode = forceEncode;
			for(int i=0;i<segments.length;i++) {
				if(segments[i] != segment) {
					if(persistent)
						container.activate(segments[i], 1);
				}
				if((segments[i] == null) || !segments[i].isEncoded()) {
					ret = true;
					if(segments[i] != segment && persistent)
						container.deactivate(segments[i], 1);
					break;
				}
				if(segments[i] != segment && persistent)
					container.deactivate(segments[i], 1);
			}
		}
		if(encode) segment.forceEncode(container, context);
		if(ret) return;
		if(persistent)
			container.activate(cb, 1);
		cb.onBlockSetFinished(this, container, context);
		if(persistent)
			container.deactivate(cb, 1);
		if(countDataBlocks > 32) {
			if(persistent)
				container.activate(parent, 1);
			parent.onMajorProgress(container);
		}
	}

	public boolean segmentHasURIs(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Segment has URIs: "+segment);
		synchronized(this) {
			if(haveSentMetadata) {
				return false;
			}

			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				boolean hasURIs = segments[i].hasURIs();
				if(persistent && segments[i] != segment)
					container.deactivate(segments[i], 1);
				if(!hasURIs) {
					if(logMINOR) Logger.minor(this, "Segment does not have URIs: "+segments[i]);
					return false;
				}
			}
		}

		if(logMINOR) Logger.minor(this, "Have URIs from all segments");
		encodeMetadata(container, context, segment);
		return true;
	}

	private void encodeMetadata(ObjectContainer container, ClientContext context, SplitFileInserterSegment dontDeactivateSegment) {
		context.jobRunner.setCommitThisTransaction();
		boolean missingURIs;
		Metadata m = null;
		ClientCHK[] dataURIs = new ClientCHK[countDataBlocks];
		ClientCHK[] checkURIs = new ClientCHK[countCheckBlocks];
		synchronized(this) {
			int dpos = 0;
			int cpos = 0;
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				ClientCHK[] data = segments[i].getDataCHKs();
				System.arraycopy(data, 0, dataURIs, dpos, data.length);
				dpos += data.length;
				ClientCHK[] check = segments[i].getCheckCHKs();
				System.arraycopy(check, 0, checkURIs, cpos, check.length);
				cpos += check.length;
				if(persistent && segments[i] != dontDeactivateSegment)
					container.deactivate(segments[i], 1);
			}
			// Create metadata

			if(logMINOR) Logger.minor(this, "Data URIs: "+dataURIs.length+", check URIs: "+checkURIs.length);

			missingURIs = anyNulls(dataURIs) || anyNulls(checkURIs);

			if(persistent) {
				// Copy the URIs. We don't know what the callee wants the metadata for:
				// he might well ignore it, as in SimpleManifestPutter.onMetadata().
				// This way he doesn't need to worry about removing them.
				for(int i=0;i<dataURIs.length;i++) {
					container.activate(dataURIs[i], 5);
					dataURIs[i] = dataURIs[i].cloneKey();
				}
				for(int i=0;i<checkURIs.length;i++) {
					container.activate(checkURIs[i], 5);
					checkURIs[i] = checkURIs[i].cloneKey();
				}
			}

			if(!missingURIs) {
				// Create Metadata
				if(persistent) container.activate(cm, 5);
				ClientMetadata meta = cm;
				if(persistent) meta = meta == null ? null : meta.clone();
				m = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, meta, dataLength, archiveType, compressionCodec, decompressedLength, isMetadata);
			}
			haveSentMetadata = true;
		}
		if(missingURIs) {
			if(logMINOR) Logger.minor(this, "Missing URIs");
			// Error
			fail(new InsertException(InsertException.INTERNAL_ERROR, "Missing URIs after encoding", null), container, context);
			return;
		} else {
			if(persistent)
				container.activate(cb, 1);
			cb.onMetadata(m, this, container, context);
			if(persistent)
				container.deactivate(cb, 1);
		}
	}

	private void fail(InsertException e, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent) {
			container.store(this);
			container.activate(cb, 1);
		}
		cb.onFailure(e, this, container, context);
		if(persistent) {
			container.deactivate(cb, 1);
		}
	}

	// FIXME move this to somewhere
	private static boolean anyNulls(Object[] array) {
		for(int i=0;i<array.length;i++)
			if(array[i] == null) return true;
		return false;
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public void segmentFinished(SplitFileInserterSegment segment, ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "Segment finished: "+segment, new Exception("debug"));
		boolean allGone = true;
		if(countDataBlocks > 32) {
			if(persistent)
				container.activate(parent, 1);
			parent.onMajorProgress(container);
		}
		synchronized(this) {
			if(finished) {
				if(logMINOR) Logger.minor(this, "Finished already");
				return;
			}
			for(int i=0;i<segments.length;i++) {
				if(persistent && segments[i] != segment)
					container.activate(segments[i], 1);
				if(!segments[i].isFinished()) {
					if(logMINOR) Logger.minor(this, "Segment not finished: "+i+": "+segments[i]);
					allGone = false;
					if(persistent && segments[i] != segment)
						container.deactivate(segments[i], 1);
					break;
				}
				if(persistent && segments[i] != segment)
					container.deactivate(segments[i], 1);
			}

			InsertException e = segment.getException(container);
			if((e != null) && e.isFatal()) {
				cancel(container, context);
			} else {
				if(!allGone) return;
			}
			finished = true;
		}
		if(persistent)
			container.store(this);
		onAllFinished(container, context);
	}

	public void segmentFetchable(SplitFileInserterSegment segment, ObjectContainer container) {
		if(logMINOR) Logger.minor(this, "Segment fetchable: "+segment);
		synchronized(this) {
			if(finished) return;
			if(fetchable) return;
			for(int i=0;i<segments.length;i++) {
				if(persistent && segments[i] != segment)
					container.activate(segments[i], 1);
				if(!segments[i].isFetchable()) {
					if(logMINOR) Logger.minor(this, "Segment not fetchable: "+i+": "+segments[i]);
					if(persistent && segments[i] != segment)
						container.deactivate(segments[i], 1);
					return;
				}
				if(persistent && segments[i] != segment)
					container.deactivate(segments[i], 1);
			}
			fetchable = true;
		}
		if(persistent) {
			container.activate(cb, 1);
			container.store(this);
		}
		cb.onFetchable(this, container);
	}

	private void onAllFinished(ObjectContainer container, ClientContext context) {
		if(logMINOR) Logger.minor(this, "All finished");
		try {
			// Finished !!
			FailureCodeTracker tracker = new FailureCodeTracker(true);
			boolean allSucceeded = true;
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				InsertException e = segments[i].getException(container);
				if(e == null) continue;
				if(logMINOR) Logger.minor(this, "Failure on segment "+i+" : "+segments[i]+" : "+e, e);
				allSucceeded = false;
				if(e.errorCodes != null)
					tracker.merge(e.errorCodes);
				tracker.inc(e.getMode());
			}
			if(persistent)
				container.activate(cb, 1);
			if(allSucceeded)
				cb.onSuccess(this, container, context);
			else {
				cb.onFailure(InsertException.construct(tracker), this, container, context);
			}
		} catch (Throwable t) {
			// We MUST tell the parent *something*!
			Logger.error(this, "Caught "+t, t);
			cb.onFailure(new InsertException(InsertException.INTERNAL_ERROR), this, container, context);
		}
	}

	public void cancel(ObjectContainer container, ClientContext context) {
		if(logMINOR)
			Logger.minor(this, "Cancelling "+this);
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(persistent)
			container.store(this);
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].cancel(container, context);
		}
		// The segments will call segmentFinished, but it will ignore them because finished=true.
		// Hence we need to call the callback here, since the caller expects us to.
		if(persistent)
			container.activate(cb, 1);
		cb.onFailure(new InsertException(InsertException.CANCELLED), this, container, context);
	}

	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(container, context);
	}

	public Object getToken() {
		return token;
	}

	public long getLength() {
		return dataLength;
	}

	/** Force the remaining blocks which haven't been encoded so far to be encoded ASAP. */
	public void forceEncode(ObjectContainer container, ClientContext context) {
		if(persistent)
			container.activate(this, 1);
		Logger.minor(this, "Forcing encode on "+this);
		synchronized(this) {
			forceEncode = true;
		}
		for(int i=0;i<segments.length;i++) {
			if(persistent)
				container.activate(segments[i], 1);
			segments[i].forceEncode(container, context);
			if(persistent)
				container.deactivate(segments[i], 1);
		}
	}

	public void removeFrom(ObjectContainer container, ClientContext context) {
		// parent can remove itself
		// ctx will be removed by parent
		// cb will remove itself
		// cm will be removed by parent
		// token setter can remove token
		for(SplitFileInserterSegment segment : segments) {
			container.activate(segment, 1);
			segment.removeFrom(container, context);
		}
		container.delete(this);
	}

	public boolean objectCanUpdate(ObjectContainer container) {
		if(logDEBUG)
			Logger.debug(this, "objectCanUpdate() on "+this, new Exception("debug"));
		return true;
	}

	public boolean objectCanNew(ObjectContainer container) {
		if(finished)
			Logger.error(this, "objectCanNew but finished on "+this, new Exception("error"));
		else if(logDEBUG)
			Logger.debug(this, "objectCanNew() on "+this, new Exception("debug"));
		return true;
	}

	public void dump(ObjectContainer container) {
		System.out.println("This: "+this);
		System.out.println("Persistent: "+persistent);
		System.out.println("Finished: "+finished);
		System.out.println("Data length: "+dataLength);
		System.out.println("Segment count: "+segments.length);
		System.out.println("Fetchable: "+fetchable);
		container.activate(parent,1);
		System.out.println("Parent: "+parent);
		parent.dump(container);
		container.deactivate(parent, 1);
	}

}
