package freenet.client.async;

import java.net.MalformedURLException;

import freenet.client.FECCodec;
import freenet.client.FECJob;
import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.client.StandardOnionFECCodec;
import freenet.client.StandardOnionFECCodec.StandardOnionFECCodecEncoderCallback;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.SerializableToFieldSetBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class SplitFileInserterSegment implements PutCompletionCallback,
		StandardOnionFECCodecEncoderCallback {

	private static boolean logMINOR;

	final SplitFileInserter parent;

	final FECCodec splitfileAlgo;

	final Bucket[] dataBlocks;

	final Bucket[] checkBlocks;

	final ClientCHK[] dataURIs;

	final ClientCHK[] checkURIs;

	final SingleBlockInserter[] dataBlockInserters;

	final SingleBlockInserter[] checkBlockInserters;

	final InserterContext blockInsertContext;

	final int segNo;

	private boolean encoded;

	private boolean finished;

	private final boolean getCHKOnly;

	private boolean hasURIs;

	private InserterException toThrow;

	private final FailureCodeTracker errors;

	private int blocksGotURI;

	private int blocksCompleted;

	private boolean started;

	public SplitFileInserterSegment(SplitFileInserter parent,
			FECCodec splitfileAlgo, Bucket[] origDataBlocks,
			InserterContext blockInsertContext, boolean getCHKOnly, int segNo) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.errors = new FailureCodeTracker(true);
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		this.dataBlocks = origDataBlocks;
		int checkBlockCount = splitfileAlgo == null ? 0 : splitfileAlgo
				.countCheckBlocks();
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new ClientCHK[checkBlockCount];
		dataURIs = new ClientCHK[origDataBlocks.length];
		dataBlockInserters = new SingleBlockInserter[dataBlocks.length];
		checkBlockInserters = new SingleBlockInserter[checkBlocks.length];
		parent.parent.addBlocks(dataURIs.length + checkURIs.length);
		parent.parent.addMustSucceedBlocks(dataURIs.length + checkURIs.length);
		this.segNo = segNo;
	}

	/**
	 * Resume an insert segment
	 * 
	 * @throws ResumeException
	 */
	public SplitFileInserterSegment(SplitFileInserter parent,
			SimpleFieldSet fs, short splitfileAlgorithm, InserterContext ctx,
			boolean getCHKOnly, int segNo) throws ResumeException {
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.blockInsertContext = ctx;
		this.segNo = segNo;
		if (!"SplitFileInserterSegment".equals(fs.get("Type")))
			throw new ResumeException("Wrong Type: " + fs.get("Type"));
		finished = Fields.stringToBool(fs.get("Finished"), false);
		encoded = true;
		started = Fields.stringToBool(fs.get("Started"), false);
		SimpleFieldSet errorsFS = fs.subset("Errors");
		if (errorsFS != null)
			this.errors = new FailureCodeTracker(true, errorsFS);
		else
			this.errors = new FailureCodeTracker(true);
		if (finished && !errors.isEmpty())
			toThrow = InserterException.construct(errors);
		blocksGotURI = 0;
		blocksCompleted = 0;
		SimpleFieldSet dataFS = fs.subset("DataBlocks");
		if (dataFS == null)
			throw new ResumeException("No data blocks");
		String tmp = dataFS.get("Count");
		if (tmp == null)
			throw new ResumeException("No data block count");
		int dataBlockCount;
		try {
			dataBlockCount = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt data blocks count: " + e + " : "
					+ tmp);
		}

		hasURIs = true;

		dataBlocks = new Bucket[dataBlockCount];
		dataURIs = new ClientCHK[dataBlockCount];
		dataBlockInserters = new SingleBlockInserter[dataBlockCount];

		// Check blocks first, because if there are missing check blocks, we
		// need
		// all the data blocks so we can re-encode.

		SimpleFieldSet checkFS = fs.subset("CheckBlocks");
		if (checkFS != null) {
			tmp = checkFS.get("Count");
			if (tmp == null)
				throw new ResumeException(
						"Check blocks but no check block count");
			int checkBlockCount;
			try {
				checkBlockCount = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				throw new ResumeException("Corrupt check blocks count: " + e
						+ " : " + tmp);
			}
			checkBlocks = new Bucket[checkBlockCount];
			checkURIs = new ClientCHK[checkBlockCount];
			checkBlockInserters = new SingleBlockInserter[checkBlockCount];
			for (int i = 0; i < checkBlockCount; i++) {
				String index = Integer.toString(i);
				SimpleFieldSet blockFS = checkFS.subset(index);
				if (blockFS == null) {
					hasURIs = false;
					encoded = false;
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					continue;
				}
				tmp = blockFS.get("URI");
				if (tmp != null) {
					try {
						checkURIs[i] = (ClientCHK) ClientKey
								.getBaseKey(new FreenetURI(tmp));
						blocksGotURI++;
					} catch (MalformedURLException e) {
						throw new ResumeException("Corrupt URI: " + e + " : "
								+ tmp);
					}
				} else {
					hasURIs = false;
				}
				boolean blockFinished = Fields.stringToBool(blockFS
						.get("Finished"), false)
						&& checkURIs[i] != null;
				if (blockFinished && checkURIs[i] == null) {
					Logger.error(this, "No URI for check block " + i + " of "
							+ segNo + " yet apparently finished?");
					encoded = false;
				}
				// Read data; only necessary if the block isn't finished.
				if (!blockFinished) {
					SimpleFieldSet bucketFS = blockFS.subset("Data");
					if (bucketFS != null) {
						try {
							checkBlocks[i] = SerializableToFieldSetBucketUtil
									.create(bucketFS, ctx.random,
											ctx.persistentFileTracker);
							if (logMINOR)
								Logger.minor(this, "Check block " + i + " : "
										+ checkBlocks[i]);
						} catch (CannotCreateFromFieldSetException e) {
							Logger.error(this,
									"Failed to deserialize check block " + i
											+ " of " + segNo + " : " + e, e);
							// Re-encode it.
							checkBlocks[i] = null;
							encoded = false;
						}
						if (checkBlocks[i] == null)
							throw new ResumeException(
									"Check block "
											+ i
											+ " of "
											+ segNo
											+ " not finished but no data (create returned null)");
					}
					// Don't create fetcher yet; that happens in start()
				} else
					blocksCompleted++;
				if (checkBlocks[i] == null && checkURIs[i] == null) {
					Logger.normal(this, "Clearing encoded because block " + i
							+ " of " + segNo + " missing");
					encoded = false;
				}
				checkFS.removeSubset(index);
			}
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm,
					dataBlockCount, checkBlocks.length);
		} else {
			Logger.normal(this, "Not encoded because no check blocks");
			encoded = false;
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm,
					dataBlockCount);
			int checkBlocksCount = splitfileAlgo.countCheckBlocks();
			this.checkURIs = new ClientCHK[checkBlocksCount];
			this.checkBlocks = new Bucket[checkBlocksCount];
			this.checkBlockInserters = new SingleBlockInserter[checkBlocksCount];
			hasURIs = false;
		}

		for (int i = 0; i < dataBlockCount; i++) {
			String index = Integer.toString(i);
			SimpleFieldSet blockFS = dataFS.subset(index);
			if (blockFS == null)
				throw new ResumeException("No data block " + i + " on segment "
						+ segNo);
			tmp = blockFS.get("URI");
			if (tmp != null) {
				try {
					dataURIs[i] = (ClientCHK) ClientKey
							.getBaseKey(new FreenetURI(tmp));
					blocksGotURI++;
				} catch (MalformedURLException e) {
					throw new ResumeException("Corrupt URI: " + e + " : " + tmp);
				}
			} else
				hasURIs = false;
			boolean blockFinished = Fields.stringToBool(
					blockFS.get("Finished"), false);
			if (blockFinished && dataURIs[i] == null)
				throw new ResumeException("Block " + i + " of " + segNo
						+ " finished but no URI");
			if (!blockFinished)
				finished = false;
			else
				blocksCompleted++;

			// Read data
			SimpleFieldSet bucketFS = blockFS.subset("Data");
			if (bucketFS == null) {
				if (!blockFinished)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " not finished but no data");
				else if (splitfileAlgorithm > 0 && !encoded)
					throw new ResumeException("Block " + i + " of " + segNo
							+ " data not available even though not encoded");
			} else {
				try {
					dataBlocks[i] = SerializableToFieldSetBucketUtil.create(
							bucketFS, ctx.random, ctx.persistentFileTracker);
					if (logMINOR)
						Logger.minor(this, "Data block " + i + " : "
								+ checkBlocks[i]);
				} catch (CannotCreateFromFieldSetException e) {
					throw new ResumeException("Failed to deserialize block "
							+ i + " of " + segNo + " : " + e, e);
				}
				if (dataBlocks[i] == null)
					throw new ResumeException(
							"Block "
									+ i
									+ " of "
									+ segNo
									+ " could not serialize data (create returned null) from "
									+ bucketFS);
				// Don't create fetcher yet; that happens in start()
			}
			dataFS.removeSubset(index);
		}

		if (!encoded) {
			finished = false;
			hasURIs = false;
			for (int i = 0; i < dataBlocks.length; i++)
				if (dataBlocks[i] == null)
					throw new ResumeException("Missing data block " + i
							+ " and need to reconstruct check blocks");
		}
		parent.parent.addBlocks(dataURIs.length + checkURIs.length);
		parent.parent.addMustSucceedBlocks(dataURIs.length + checkURIs.length);
	}

	public synchronized SimpleFieldSet getProgressFieldset() {
		SimpleFieldSet fs = new SimpleFieldSet(false); // these get BIG
		fs.putSingle("Type", "SplitFileInserterSegment");
		fs.put("Finished", finished);
		// If true, check blocks which are null are finished
		fs.put("Encoded", encoded);
		// If true, data blocks which are null are finished
		fs.put("Started", started);
		fs.tput("Errors", errors.toFieldSet(false));
		SimpleFieldSet dataFS = new SimpleFieldSet(false);
		dataFS.put("Count", dataBlocks.length);
		for (int i = 0; i < dataBlocks.length; i++) {
			SimpleFieldSet block = new SimpleFieldSet(false);
			if (dataURIs[i] != null)
				block.putSingle("URI", dataURIs[i].getURI().toString());
			SingleBlockInserter sbi = dataBlockInserters[i];
			// If started, then sbi = null => block finished.
			boolean finished = started && sbi == null;
			if (started) {
				block.put("Finished", finished);
			}
			Bucket data = dataBlocks[i];
			if (data == null && finished) {
				// Ignore
				if (logMINOR)
					Logger.minor(this, "Could not save to disk: null");
			} else if (data instanceof SerializableToFieldSetBucket) {
				SimpleFieldSet tmp = ((SerializableToFieldSetBucket) data)
						.toFieldSet();
				if (tmp == null) {
					if (logMINOR)
						Logger.minor(this, "Could not save to disk: " + data);
					return null;
				}
				block.put("Data", tmp);
			} else {
				if (logMINOR)
					Logger.minor(this,
							"Could not save to disk (not serializable to fieldset): "
									+ data);
				return null;
			}
			if (!block.isEmpty())
				dataFS.put(Integer.toString(i), block);
		}
		fs.put("DataBlocks", dataFS);
		SimpleFieldSet checkFS = new SimpleFieldSet(false);
		checkFS.put("Count", checkBlocks.length);
		for (int i = 0; i < checkBlocks.length; i++) {
			SimpleFieldSet block = new SimpleFieldSet(false);
			if (checkURIs[i] != null)
				block.putSingle("URI", checkURIs[i].getURI().toString());
			SingleBlockInserter sbi = checkBlockInserters[i];
			// If encoded, then sbi == null => block finished
			boolean finished = encoded && sbi == null && checkURIs[i] != null;
			if (encoded) {
				block.put("Finished", finished);
			}
			if (!finished) {
				Bucket data = checkBlocks[i];
				if (data != null
						&& data instanceof SerializableToFieldSetBucket) {
					SimpleFieldSet tmp = ((SerializableToFieldSetBucket) data)
							.toFieldSet();
					if (tmp == null)
						Logger.error(this, "Could not serialize " + data
								+ " - check block " + i + " of " + segNo);
					else
						block.put("Data", tmp);
				} else if (encoded) {
					Logger.error(this,
							"Could not save to disk (null or not serializable to fieldset): "
									+ data);
					return null;
				}
			}
			if (!block.isEmpty())
				checkFS.put(Integer.toString(i), block);
		}
		fs.put("CheckBlocks", checkFS);
		return fs;
	}

	public void start() throws InserterException {
		if (logMINOR)
			Logger.minor(this, "Starting segment " + segNo + " of " + parent
					+ " (" + parent.dataLength + "): " + this + " ( finished="
					+ finished + " encoded=" + encoded + " hasURIs=" + hasURIs
					+ ')');
		boolean fin = true;

		for (int i = 0; i < dataBlockInserters.length; i++) {
			if (dataBlocks[i] != null) { // else already finished on creation
				dataBlockInserters[i] = new SingleBlockInserter(parent.parent,
						dataBlocks[i], (short) -1, FreenetURI.EMPTY_CHK_URI,
						blockInsertContext, this, false, CHKBlock.DATA_LENGTH,
						i, getCHKOnly, false, false, parent.token);
				dataBlockInserters[i].schedule();
				fin = false;
			} else {
				parent.parent.completedBlock(true);
			}
		}
		// parent.parent.notifyClients();
		started = true;
		if (!encoded) {
			if (logMINOR)
				Logger.minor(this, "Segment " + segNo + " of " + parent + " ("
						+ parent.dataLength + ") is not encoded");
			if (splitfileAlgo != null) {
				if (logMINOR)
					Logger.minor(this, "Encoding segment " + segNo + " of "
							+ parent + " (" + parent.dataLength + ')');
				// Encode blocks
				synchronized(this) {
					if(!encoded){
						splitfileAlgo.addToQueue(new FECJob(dataBlocks, checkBlocks, CHKBlock.DATA_LENGTH, blockInsertContext.persistentBucketFactory, this, false));
					}
				}				
				fin = false;
			}
		} else {
			for (int i = 0; i < checkBlockInserters.length; i++) {
				if (checkBlocks[i] != null) {
					checkBlockInserters[i] = new SingleBlockInserter(
							parent.parent, checkBlocks[i], (short) -1,
							FreenetURI.EMPTY_CHK_URI, blockInsertContext, this,
							false, CHKBlock.DATA_LENGTH, i + dataBlocks.length,
							getCHKOnly, false, false, parent.token);
					checkBlockInserters[i].schedule();
					fin = false;
				} else
					parent.parent.completedBlock(true);
			}
			onEncodedSegment();
			parent.encodedSegment(this);
		}
		if (hasURIs) {
			parent.segmentHasURIs(this);
		}
		boolean fetchable;
		synchronized (this) {
			fetchable = (blocksCompleted > dataBlocks.length);
		}
		if (fetchable)
			parent.segmentFetchable(this);
		if (fin)
			finish();
		if (finished) {
			parent.segmentFinished(this);
		}
	}

	public void onDecodedSegment() {} // irrevelant

	public void onEncodedSegment() {
		// Start the inserts
		try {
			for (int i = 0; i < checkBlockInserters.length; i++) {
				checkBlockInserters[i] = new SingleBlockInserter(parent.parent,
						checkBlocks[i], (short) -1, FreenetURI.EMPTY_CHK_URI,
						blockInsertContext, this, false, CHKBlock.DATA_LENGTH,
						i + dataBlocks.length, getCHKOnly, false, false,
						parent.token);
				checkBlockInserters[i].schedule();
			}
		} catch (Throwable t) {
			Logger.error(this, "Caught " + t + " while encoding " + this, t);
			InserterException ex = new InserterException(
					InserterException.INTERNAL_ERROR, t, null);
			finish(ex);
		}

		synchronized (this) {
			encoded = true;
		}

		// Tell parent only after have started the inserts.
		// Because of the counting.
		parent.encodedSegment(this);

		synchronized (this) {
			for (int i = 0; i < dataBlockInserters.length; i++) {
				if (dataBlockInserters[i] == null && dataBlocks[i] != null) {
					dataBlocks[i].free();
					dataBlocks[i] = null;
				}
			}
		}
	}

	private void finish(InserterException ex) {
		if (logMINOR)
			Logger.minor(this, "Finishing " + this + " with " + ex, ex);
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = ex;
		}
		parent.segmentFinished(this);
	}

	private void finish() {
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			toThrow = InserterException.construct(errors);
		}
		parent.segmentFinished(this);
	}

	public void onEncode(BaseClientKey k, ClientPutState state) {
		ClientCHK key = (ClientCHK) k;
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		synchronized (this) {
			if (finished)
				return;
			if (x >= dataBlocks.length) {
				if (checkURIs[x - dataBlocks.length] != null) {
					return;
				}
				checkURIs[x - dataBlocks.length] = key;
			} else {
				if (dataURIs[x] != null) {
					return;
				}
				dataURIs[x] = key;
			}
			blocksGotURI++;
			if (blocksGotURI != dataBlocks.length + checkBlocks.length)
				return;
			// Double check
			for (int i = 0; i < checkURIs.length; i++) {
				if (checkURIs[i] == null) {
					Logger.error(this, "Check URI " + i + " is null");
					return;
				}
			}
			for (int i = 0; i < dataURIs.length; i++) {
				if (dataURIs[i] == null) {
					Logger.error(this, "Data URI " + i + " is null");
					return;
				}
			}
			hasURIs = true;
		}
		parent.segmentHasURIs(this);
	}

	public void onSuccess(ClientPutState state) {
		if (parent.parent.isCancelled()) {
			parent.cancel();
			return;
		}
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		completed(x);
	}

	public void onFailure(InserterException e, ClientPutState state) {
		if (parent.parent.isCancelled()) {
			parent.cancel();
			return;
		}
		SingleBlockInserter sbi = (SingleBlockInserter) state;
		int x = sbi.token;
		errors.merge(e);
		completed(x);
	}

	private void completed(int x) {
		int total = innerCompleted(x);
		if (total == -1)
			return;
		if (total == dataBlockInserters.length) {
			parent.segmentFetchable(this);
		}
		if (total != dataBlockInserters.length + checkBlockInserters.length)
			return;
		finish();
	}

	/**
	 * Called when a block has completed.
	 * 
	 * @param x
	 *            The block number.
	 * @return -1 if the segment has already finished, otherwise the number of
	 *         completed blocks.
	 */
	private synchronized int innerCompleted(int x) {
		if (logMINOR)
			Logger.minor(this, "Completed: " + x + " on " + this
					+ " ( completed=" + blocksCompleted + ", total="
					+ (dataBlockInserters.length + checkBlockInserters.length));

		if (finished)
			return -1;
		if (x >= dataBlocks.length) {
			x -= dataBlocks.length;
			if (checkBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: check block " + x + " on "
						+ this, new Exception());
				return blocksCompleted;
			}
			checkBlockInserters[x] = null;
			checkBlocks[x].free();
			checkBlocks[x] = null;
		} else {
			if (dataBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: data block " + x + " on "
						+ this, new Exception());
				return blocksCompleted;
			}
			dataBlockInserters[x] = null;
			if (encoded) {
				dataBlocks[x].free();
				dataBlocks[x] = null;
			}
		}
		blocksCompleted++;
		return blocksCompleted;
	}

	public synchronized boolean isFinished() {
		return finished;
	}

	public boolean isEncoded() {
		return encoded;
	}

	public int countCheckBlocks() {
		return checkBlocks.length;
	}

	public int countDataBlocks() {
		return dataBlocks.length;
	}

	public ClientCHK[] getCheckCHKs() {
		return checkURIs;
	}

	public ClientCHK[] getDataCHKs() {
		return dataURIs;
	}

	InserterException getException() {
		synchronized (this) {
			return toThrow;
		}
	}

	public void cancel() {
		synchronized (this) {
			if (finished)
				return;
			finished = true;
			if (toThrow != null)
				toThrow = new InserterException(InserterException.CANCELLED);
		}
		for (int i = 0; i < dataBlockInserters.length; i++) {
			SingleBlockInserter sbi = dataBlockInserters[i];
			if (sbi != null)
				sbi.cancel();
			Bucket d = dataBlocks[i];
			if (d != null) {
				d.free();
				dataBlocks[i] = null;
			}
		}
		for (int i = 0; i < checkBlockInserters.length; i++) {
			SingleBlockInserter sbi = checkBlockInserters[i];
			if (sbi != null)
				sbi.cancel();
			Bucket d = checkBlocks[i];
			if (d != null) {
				d.free();
				checkBlocks[i] = null;
			}
		}
		parent.segmentFinished(this);
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		Logger.error(this, "Illegal transition in SplitFileInserterSegment: "
				+ oldState + " -> " + newState);
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		Logger.error(this, "Got onMetadata from " + state);
	}

	public void onBlockSetFinished(ClientPutState state) {
		// Ignore
		Logger.error(this, "Should not happen: onBlockSetFinished(" + state
				+ ") on " + this);
	}

	public synchronized boolean hasURIs() {
		return hasURIs;
	}

	public synchronized boolean isFetchable() {
		return blocksCompleted >= dataBlocks.length;
	}

	public void onFetchable(ClientPutState state) {
		// Ignore
	}

	/**
	 * Force the remaining blocks which haven't been encoded so far to be
	 * encoded ASAP.
	 */
	public void forceEncode() {
		blockInsertContext.backgroundBlockEncoder.queue(dataBlockInserters);
		blockInsertContext.backgroundBlockEncoder.queue(checkBlockInserters);
	}
}
