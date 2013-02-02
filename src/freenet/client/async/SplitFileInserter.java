/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.crypt.HashResult;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.support.Executor;
import freenet.support.HexUtil;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketTools;
import freenet.support.math.MersenneTwister;

public class SplitFileInserter implements ClientPutState {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	final BaseClientPutter parent;
	final InsertContext ctx;
	final PutCompletionCallback cb;
	final long dataLength;
	final COMPRESSOR_TYPE compressionCodec;
	final short splitfileAlgorithm;
	/** The number of data blocks in a typical segment. Does not include cross-check blocks. */
	final int segmentSize;
	/** The number of check blocks in a typical segment. Does not include cross-check blocks. */
	final int deductBlocksFromSegments;
	/** The number of cross-check blocks in any segment, or in any cross-segment. */
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
	final HashResult[] hashes;
	final byte[] hashThisLayerOnly;
	private byte splitfileCryptoAlgorithm;
	private byte[] splitfileCryptoKey;
	private final boolean specifySplitfileKeyInMetadata;
	private final boolean realTimeFlag;
	
	public final long topSize;
	public final long topCompressedSize;
	
	// Cross-segment splitfile redundancy
	private final int crossCheckBlocks;
	private final SplitFileInserterCrossSegment[] crossSegments;

	// A persistent hashCode is helpful in debugging, and also means we can put
	// these objects into sets etc when we need to.

	private final int hashCode;
	
	@Override
	public int hashCode() {
		return hashCode;
	}

	/**
	 * zero arg c'tor for db4o on jamvm
	 */
	@SuppressWarnings("unused")
	private SplitFileInserter() {
		topSize = 0;
		topCompressedSize = 0;
		token = null;
		splitfileAlgorithm = 0;
		specifySplitfileKeyInMetadata = false;
		segments = null;
		segmentSize = 0;
		persistent = false;
		parent = null;
		isMetadata = false;
		hashes = null;
		hashThisLayerOnly = null;
		hashCode = 0;
		getCHKOnly = false;
		deductBlocksFromSegments = 0;
		decompressedLength = 0;
		dataLength = 0;
		ctx = null;
		crossSegments = null;
		crossCheckBlocks = 0;
		countDataBlocks = 0;
		countCheckBlocks = 0;
		compressionCodec = null;
		cm = null;
		checkSegmentSize = 0;
		cb = null;
		archiveType = null;
		realTimeFlag = false;
	}

	public SplitFileInserter(BaseClientPutter put, PutCompletionCallback cb, Bucket data, COMPRESSOR_TYPE bestCodec, long decompressedLength, ClientMetadata clientMetadata, InsertContext ctx, boolean getCHKOnly, boolean isMetadata, Object token, ARCHIVE_TYPE archiveType, boolean freeData, boolean persistent, boolean realTimeFlag, ObjectContainer container, ClientContext context, HashResult[] hashes, byte[] hashThisLayerOnly, long origTopSize, long origTopCompressedSize, byte cryptoAlgorithm, byte[] splitfileKey) throws InsertException {
		hashCode = super.hashCode();
		if(put == null) throw new NullPointerException();
		this.parent = put;
		this.realTimeFlag = realTimeFlag;
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
		this.hashes = hashes;
		this.topSize = origTopSize;
		this.topCompressedSize = origTopCompressedSize;
		this.hashThisLayerOnly = hashThisLayerOnly;
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
		
		// Segment size cannot be greater than ctx.splitfileSegmentDataBlocks.
		// But IT CAN BE SMALLER!
		int segs;
		CompatibilityMode cmode = ctx.getCompatibilityMode();
		if(cmode == CompatibilityMode.COMPAT_1250_EXACT) {
			segs = (countDataBlocks + 128 - 1) / 128;
			segmentSize = 128;
			deductBlocksFromSegments = 0;
		} else {
			if(cmode == CompatibilityMode.COMPAT_1251) {
				// Max 131 blocks per segment.
				segs = (countDataBlocks + 131 - 1) / 131;
			} else {
				// Algorithm from evanbd, see bug #2931.
				if(countDataBlocks > 520) {
					segs = (countDataBlocks + 128 - 1) / 128;
				} else if(countDataBlocks > 393) {
					//maxSegSize = 130;
					segs = 4;
				} else if(countDataBlocks > 266) {
					//maxSegSize = 131;
					segs = 3;
				} else if(countDataBlocks > 136) {
					//maxSegSize = 133;
					segs = 2;
				} else {
					//maxSegSize = 136;
					segs = 1;
				}
			}
			int segSize = (countDataBlocks + segs - 1) / segs;
			if(ctx.splitfileSegmentDataBlocks < segSize) {
				segs = (countDataBlocks + ctx.splitfileSegmentDataBlocks - 1) / ctx.splitfileSegmentDataBlocks;
				segSize = (countDataBlocks + segs - 1) / segs;
			}
			segmentSize = segSize;
			if(cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal()) {
				// Even with basic even segment splitting, it is possible for the last segment to be a lot smaller than the rest.
				// So drop a single data block from each of the last [segmentSize-lastSegmentSize] segments instead.
				// Hence all the segments are within 1 block of segmentSize.
				int lastSegmentSize = countDataBlocks - (segmentSize * (segs - 1));
				deductBlocksFromSegments = segmentSize - lastSegmentSize;
			} else {
				deductBlocksFromSegments = 0;
			}
		}
		
		int crossCheckBlocks = 0;
		
		// Cross-segment splitfile redundancy becomes useful at 20 segments.
		if(segs >= 20 && (cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal())) {
			// The optimal number of cross-check blocks per segment (and per cross-segment since there are the same number of cross-segments as segments) is 3.
			crossCheckBlocks = 3;
		}
		
		this.crossCheckBlocks = crossCheckBlocks;
		
		if(splitfileAlgorithm == Metadata.SPLITFILE_NONREDUNDANT)
			checkSegmentSize = 0;
		else
			checkSegmentSize = FECCodec.getCheckBlocks(splitfileAlgorithm, segmentSize + crossCheckBlocks, cmode);
		
		this.persistent = persistent;
		if(persistent) {
			container.activate(parent, 1);
		}

		// Create segments
		this.splitfileCryptoAlgorithm = cryptoAlgorithm;
		if(splitfileKey != null) {
			this.splitfileCryptoKey = splitfileKey;
			specifySplitfileKeyInMetadata = true;
		} else if(cmode == CompatibilityMode.COMPAT_CURRENT || cmode.ordinal() >= CompatibilityMode.COMPAT_1255.ordinal()) {
			if(hashThisLayerOnly != null) {
				this.splitfileCryptoKey = Metadata.getCryptoKey(hashThisLayerOnly);
			} else {
				if(persistent) {
					// array elements are treated as part of the parent object, but the hashes themselves may not be activated?
					for(HashResult res : hashes) {
						if(res == null) throw new NullPointerException();
						container.activate(res, Integer.MAX_VALUE);
					}
				}
				this.splitfileCryptoKey = Metadata.getCryptoKey(hashes);
			}
			specifySplitfileKeyInMetadata = false;
		} else
			specifySplitfileKeyInMetadata = false;
		segments = splitIntoSegments(segmentSize, crossCheckBlocks, segs, deductBlocksFromSegments, dataBuckets, context.mainExecutor, container, context, persistent, put, cryptoAlgorithm, splitfileCryptoKey);
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
		
		if(crossCheckBlocks != 0) {
			byte[] seed = Metadata.getCrossSegmentSeed(hashes, hashThisLayerOnly);
			if(logMINOR) Logger.minor(this, "Cross-segment seed: "+HexUtil.bytesToHex(seed));
			Random random = new MersenneTwister(seed);
			// Cross segment redundancy: Allocate the blocks.
			crossSegments = new SplitFileInserterCrossSegment[segs];
			int segLen = segmentSize;
			for(int i=0;i<crossSegments.length;i++) {
				if(logMINOR) Logger.minor(this, "Allocating blocks for cross segment "+i);
				if(segments.length - i == deductBlocksFromSegments) {
					segLen--;
				}

				SplitFileInserterCrossSegment seg = new SplitFileInserterCrossSegment(persistent, segLen, crossCheckBlocks, put, splitfileAlgorithm, this, i);
				crossSegments[i] = seg;
				for(int j=0;j<segLen;j++) {
					// Allocate random data blocks
					allocateCrossDataBlock(seg, random);
				}
				for(int j=0;j<crossCheckBlocks;j++) {
					// Allocate check blocks
					allocateCrossCheckBlock(seg, random);
				}
				if(persistent) seg.storeTo(container);
			}
		} else {
			crossSegments = null;
		}
		
		
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

	private void allocateCrossDataBlock(SplitFileInserterCrossSegment segment, Random random) {
		int x = 0;
		for(int i=0;i<10;i++) {
			x = random.nextInt(segments.length);
			SplitFileInserterSegment seg = segments[x];
			int blockNum = seg.allocateCrossDataBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		for(int i=0;i<segments.length;i++) {
			x++;
			if(x == segments.length) x = 0;
			SplitFileInserterSegment seg = segments[x];
			int blockNum = seg.allocateCrossDataBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		throw new IllegalStateException("Unable to allocate cross data block!");
	}

	private void allocateCrossCheckBlock(SplitFileInserterCrossSegment segment, Random random) {
		int x = 0;
		for(int i=0;i<10;i++) {
			x = random.nextInt(segments.length);
			SplitFileInserterSegment seg = segments[x];
			int blockNum = seg.allocateCrossCheckBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		for(int i=0;i<segments.length;i++) {
			x++;
			if(x == segments.length) x = 0;
			SplitFileInserterSegment seg = segments[x];
			int blockNum = seg.allocateCrossCheckBlock(segment, random);
			if(blockNum >= 0) {
				segment.addDataBlock(seg, blockNum);
				return;
			}
		}
		throw new IllegalStateException("Unable to allocate cross data block!");
	}

	/**
	 * Group the blocks into segments.
	 * @param deductBlocksFromSegments 
	 */
	private SplitFileInserterSegment[] splitIntoSegments(int segmentSize, int crossCheckBlocks, int segCount, int deductBlocksFromSegments, Bucket[] origDataBlocks, Executor executor, ObjectContainer container, ClientContext context, boolean persistent, BaseClientPutter putter, byte cryptoAlgorithm, byte[] splitfileCryptoKey) {
		int dataBlocks = origDataBlocks.length;

		ArrayList<SplitFileInserterSegment> segs = new ArrayList<SplitFileInserterSegment>();

		CompatibilityMode cmode = ctx.getCompatibilityMode();
		// First split the data up
		if(segCount == 1) {
			// Single segment
			SplitFileInserterSegment onlySeg = new SplitFileInserterSegment(this, persistent, realTimeFlag, putter, splitfileAlgorithm, crossCheckBlocks, FECCodec.getCheckBlocks(splitfileAlgorithm, origDataBlocks.length + crossCheckBlocks, cmode), origDataBlocks, ctx, getCHKOnly, 0, cryptoAlgorithm, splitfileCryptoKey, container);
			segs.add(onlySeg);
		} else {
			int j = 0;
			int segNo = 0;
			int data = segmentSize;
			int check = FECCodec.getCheckBlocks(splitfileAlgorithm, data + crossCheckBlocks, cmode);
			for(int i=segmentSize;;) {
				if(i > dataBlocks) i = dataBlocks;
				if(data > (i-j)) {
					// Last segment.
					assert(segNo == segCount-1);
					data = i-j;
					check = FECCodec.getCheckBlocks(splitfileAlgorithm, data + crossCheckBlocks, cmode);
				}
				Bucket[] seg = new Bucket[i-j];
				System.arraycopy(origDataBlocks, j, seg, 0, data);
				j = i;
				for(int x=0;x<seg.length;x++)
					if(seg[x] == null) throw new NullPointerException("In splitIntoSegs: "+x+" is null of "+seg.length+" of "+segNo);
				SplitFileInserterSegment s = new SplitFileInserterSegment(this, persistent, realTimeFlag, putter, splitfileAlgorithm, crossCheckBlocks, check, seg, ctx, getCHKOnly, segNo, cryptoAlgorithm, splitfileCryptoKey, container);
				segs.add(s);
				
				if(deductBlocksFromSegments != 0)
					if(logMINOR) Logger.minor(this, "INSERTING: Segment "+segNo+" of "+segCount+" : "+data+" data blocks "+check+" check blocks");

				segNo++;
				if(i == dataBlocks) break;
				// Deduct one block from each later segment, rather than having a really short last segment.
				if(segCount - segNo == deductBlocksFromSegments) {
					data--;
					// Don't change check.
				}
				i += data;
			}
			assert(segNo == segCount);
		}
		if(persistent)
			container.activate(parent, 1);
		parent.notifyClients(container, context);
		return segs.toArray(new SplitFileInserterSegment[segs.size()]);
	}

	public void start(ObjectContainer container, final ClientContext context) throws InsertException {
		if(crossCheckBlocks != 0) {
			for(SplitFileInserterCrossSegment seg : crossSegments) {
				if(persistent)
					container.activate(seg, 1);
				seg.start(container, context);
				if(persistent)
					container.deactivate(seg, 1);
			}
		}
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
		ClientCHK[] dataURIs = new ClientCHK[countDataBlocks + crossCheckBlocks * segments.length];
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
				boolean allowTopBlocks = topSize != 0;
				int req = 0;
				int total = 0;
				long data = 0;
				long compressed = 0;
				boolean topDontCompress = false;
				short topCompatibilityMode = 0;
				if(allowTopBlocks) {
					boolean wasActive = true;
					boolean ctxWasActive = true;
					if(persistent) {
						wasActive = container.ext().isActive(parent);
						if(!wasActive)
							container.activate(parent, 1);
						ctxWasActive = container.ext().isActive(ctx);
						if(!ctxWasActive)
							container.activate(ctx, 1);
					}
					req = parent.getMinSuccessFetchBlocks();
					total = parent.totalBlocks;
					if(!wasActive) container.deactivate(parent, 1);
					data = topSize;
					compressed = topCompressedSize;
				}
				if(persistent) container.activate(hashes, Integer.MAX_VALUE);
				HashResult[] h;
				if(persistent) h = HashResult.copy(hashes);
				else h = hashes;
				if(persistent) container.activate(compressionCodec, Integer.MAX_VALUE);
				if(persistent) container.activate(archiveType, Integer.MAX_VALUE);
				m = new Metadata(splitfileAlgorithm, dataURIs, checkURIs, segmentSize, checkSegmentSize, deductBlocksFromSegments, meta, dataLength, archiveType, compressionCodec, decompressedLength, isMetadata, h, hashThisLayerOnly, data, compressed, req, total, topDontCompress, topCompatibilityMode, splitfileCryptoAlgorithm, splitfileCryptoKey, specifySplitfileKeyInMetadata, crossCheckBlocks);
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

	@Override
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
					if(logMINOR) Logger.minor(this, "Segment not finished: "+i+": "+segments[i]+" for "+this);
					allGone = false;
					if(persistent && segments[i] != segment)
						container.deactivate(segments[i], 1);
					break;
				}
				if(!segments[i].hasURIs()) {
					if(segments[i].getException(container) == null)
						Logger.error(this, "Segment finished but hasURIs() is false: "+segments[i]+" for "+this);
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

	@Override
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

	@Override
	public void schedule(ObjectContainer container, ClientContext context) throws InsertException {
		start(container, context);
	}

	@Override
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

	@Override
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
		if(hashes != null) {
			for(HashResult res : hashes) {
				container.activate(res, Integer.MAX_VALUE);
				res.removeFrom(container);
			}
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

	public void clearCrossSegment(int segNum, SplitFileInserterCrossSegment segment, ObjectContainer container, ClientContext context) {
		boolean clearedAll = true;
		synchronized(this) {
			assert(crossSegments[segNum] == segment);
			crossSegments[segNum] = null;
			for(SplitFileInserterCrossSegment seg : crossSegments) {
				if(seg != null) clearedAll = false;
			}
		}
		if(persistent) container.store(this);
		if(clearedAll) {
			for(int i=0;i<segments.length;i++) {
				if(persistent)
					container.activate(segments[i], 1);
				try {
					segments[i].start(container, context);
				} catch (InsertException e) {
					fail(e, container, context);
				}
				if(persistent)
					container.deactivate(segments[i], 1);
			}
			if(persistent)
				container.activate(parent, 1);

			if(countDataBlocks > 32)
				parent.onMajorProgress(container);
			parent.notifyClients(container, context);
		}
	}

}
