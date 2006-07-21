package freenet.client.async;

import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.CannotCreateFromFieldSetException;
import freenet.support.io.SerializableToFieldSetBucket;
import freenet.support.io.SerializableToFieldSetBucketUtil;

public class SplitFileInserterSegment implements PutCompletionCallback {

	final SplitFileInserter parent;
	final FECCodec splitfileAlgo;
	final Bucket[] dataBlocks;
	final Bucket[] checkBlocks;
	final FreenetURI[] dataURIs;
	final FreenetURI[] checkURIs;
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
	
	public SplitFileInserterSegment(SplitFileInserter parent, FECCodec splitfileAlgo, Bucket[] origDataBlocks, InserterContext blockInsertContext, boolean getCHKOnly, int segNo) {
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.errors = new FailureCodeTracker(true);
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		this.dataBlocks = origDataBlocks;
		int checkBlockCount = splitfileAlgo == null ? 0 : splitfileAlgo.countCheckBlocks();
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new FreenetURI[checkBlockCount];
		dataURIs = new FreenetURI[origDataBlocks.length];
		dataBlockInserters = new SingleBlockInserter[dataBlocks.length];
		checkBlockInserters = new SingleBlockInserter[checkBlocks.length];
		parent.parent.addBlocks(dataURIs.length+checkURIs.length);
		parent.parent.addMustSucceedBlocks(dataURIs.length+checkURIs.length);
		this.segNo = segNo;
	}
	
	/** Resume an insert segment 
	 * @throws ResumeException */
	public SplitFileInserterSegment(SplitFileInserter parent, SimpleFieldSet fs, short splitfileAlgorithm, InserterContext ctx, boolean getCHKOnly, int segNo) throws ResumeException {
		this.parent = parent;
		this.getCHKOnly = getCHKOnly;
		this.blockInsertContext = ctx;
		this.segNo = segNo;
		if(!"SplitFileInserterSegment".equals(fs.get("Type")))
			throw new ResumeException("Wrong Type: "+fs.get("Type"));
		finished = Fields.stringToBool(fs.get("Finished"), false);
		encoded = Fields.stringToBool(fs.get("Encoded"), false);
		started = Fields.stringToBool(fs.get("Started"), false);
		SimpleFieldSet errorsFS = fs.subset("Errors");
		if(errorsFS != null)
			this.errors = new FailureCodeTracker(true, errorsFS);
		else
			this.errors = new FailureCodeTracker(true);
		if(finished && !errors.isEmpty())
			toThrow = InserterException.construct(errors);
		blocksGotURI = 0;
		blocksCompleted = 0;
		SimpleFieldSet dataFS = fs.subset("DataBlocks");
		if(dataFS == null)
			throw new ResumeException("No data blocks");
		String tmp = dataFS.get("Count");
		if(tmp == null) throw new ResumeException("No data block count");
		int dataBlockCount;
		try {
			dataBlockCount = Integer.parseInt(tmp);
		} catch (NumberFormatException e) {
			throw new ResumeException("Corrupt data blocks count: "+e+" : "+tmp);
		}
		
		hasURIs = true;
		
		dataBlocks = new Bucket[dataBlockCount];
		dataURIs = new FreenetURI[dataBlockCount];
		dataBlockInserters = new SingleBlockInserter[dataBlockCount];
		for(int i=0;i<dataBlockCount;i++) {
			SimpleFieldSet blockFS = dataFS.subset(Integer.toString(i));
			if(blockFS == null) throw new ResumeException("No data block "+i+" on segment "+segNo);
			tmp = blockFS.get("URI");
			if(tmp != null) {
				try {
					dataURIs[i] = new FreenetURI(tmp);
					blocksGotURI++;
				} catch (MalformedURLException e) {
					throw new ResumeException("Corrupt URI: "+e+" : "+tmp);
				}
			} else hasURIs = false;
			boolean blockFinished = Fields.stringToBool(blockFS.get("Finished"), false);
			if(blockFinished && dataURIs[i] == null)
				throw new ResumeException("Block "+i+" of "+segNo+" finished but no URI");
			if(blockFinished && !encoded)
				throw new ResumeException("Block "+i+" of "+segNo+" finished but not encoded");
			if(!blockFinished) {
				// Read data
				SimpleFieldSet bucketFS = blockFS.subset("Data");
				if(bucketFS == null)
					throw new ResumeException("Block "+i+" of "+segNo+" not finished but no data");
				try {
					dataBlocks[i] = SerializableToFieldSetBucketUtil.create(bucketFS, ctx.random, ctx.persistentFileTracker);
				} catch (CannotCreateFromFieldSetException e) {
					throw new ResumeException("Failed to deserialize block "+i+" of "+segNo+" : "+e, e);
				}
				if(dataBlocks[i] == null)
					throw new ResumeException("Block "+i+" of "+segNo+" not finished but no data (create returned null)");
				// Don't create fetcher yet; that happens in start()
			} else blocksCompleted++;
		}

		SimpleFieldSet checkFS = fs.subset("CheckBlocks");
		if(checkFS != null) {
			tmp = checkFS.get("Count");
			if(tmp == null) throw new ResumeException("Check blocks but no check block count");
			int checkBlockCount;
			try {
				checkBlockCount = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				throw new ResumeException("Corrupt check blocks count: "+e+" : "+tmp);
			}
			checkBlocks = new Bucket[checkBlockCount];
			checkURIs = new FreenetURI[checkBlockCount];
			checkBlockInserters = new SingleBlockInserter[checkBlockCount];
			for(int i=0;i<checkBlockCount;i++) {
				SimpleFieldSet blockFS = checkFS.subset(Integer.toString(i));
				if(blockFS == null) {
					hasURIs = false;
					if(encoded) throw new ResumeException("No check block "+i+" of "+segNo);
					else continue;
				}
				tmp = blockFS.get("URI");
				if(tmp != null) {
					try {
						checkURIs[i] = new FreenetURI(tmp);
						blocksGotURI++;
					} catch (MalformedURLException e) {
						throw new ResumeException("Corrupt URI: "+e+" : "+tmp);
					}
				} else hasURIs = false;
				boolean blockFinished = Fields.stringToBool(blockFS.get("Finished"), false);
				if(blockFinished && checkURIs[i] == null)
					throw new ResumeException("Check block "+i+" of "+segNo+" finished but no URI");
				if(blockFinished && !encoded)
					throw new ResumeException("Check block "+i+" of "+segNo+" finished but not encoded");
				if(!blockFinished) {
					// Read data
					SimpleFieldSet bucketFS = blockFS.subset("Data");
					if(bucketFS == null)
						throw new ResumeException("Check block "+i+" of "+segNo+" not finished but no data");
					try {
						checkBlocks[i] = SerializableToFieldSetBucketUtil.create(bucketFS, ctx.random, ctx.persistentFileTracker);
					} catch (CannotCreateFromFieldSetException e) {
						Logger.error(this, "Failed to deserialize check block "+i+" of "+segNo+" : "+e, e);
						// Re-encode it.
						checkBlocks[i] = null;
						encoded = false;
					}
					if(checkBlocks[i] == null)
						throw new ResumeException("Check block "+i+" of "+segNo+" not finished but no data (create returned null)");
				// Don't create fetcher yet; that happens in start()
				} else blocksCompleted++;
			}
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm, dataBlockCount, checkBlocks.length);
		} else {
			splitfileAlgo = FECCodec.getCodec(splitfileAlgorithm, dataBlockCount);
			int checkBlocksCount = splitfileAlgo.countCheckBlocks();
			this.checkURIs = new FreenetURI[checkBlocksCount];
			this.checkBlocks = new Bucket[checkBlocksCount];
			this.checkBlockInserters = new SingleBlockInserter[checkBlocksCount];
			hasURIs = false;
		}
		parent.parent.addBlocks(dataURIs.length+checkURIs.length);
		parent.parent.addMustSucceedBlocks(dataURIs.length+checkURIs.length);
	}

	public synchronized SimpleFieldSet getProgressFieldset() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Type", "SplitFileInserterSegment");
		fs.put("Finished", Boolean.toString(finished));
		// If true, check blocks which are null are finished 
		fs.put("Encoded", Boolean.toString(encoded));
		// If true, data blocks which are null are finished
		fs.put("Started", Boolean.toString(started));
		errors.copyToFieldSet(fs, "Errors.", false);
		SimpleFieldSet dataFS = new SimpleFieldSet(true);
		dataFS.put("Count", Integer.toString(dataBlocks.length));
		for(int i=0;i<dataBlocks.length;i++) {
			SimpleFieldSet block = new SimpleFieldSet(true);
			if(dataURIs[i] != null)
				block.put("URI", dataURIs[i].toString());
			SingleBlockInserter sbi =
				dataBlockInserters[i];
			// If started, then sbi = null => block finished.
			boolean finished = started && sbi == null;
			if(started) {
				block.put("Finished", finished);
			}
			if(!finished) {
				Bucket data = dataBlocks[i];
				if(data instanceof SerializableToFieldSetBucket) {
					SimpleFieldSet tmp = ((SerializableToFieldSetBucket)data).toFieldSet();
					if(tmp == null) {
						Logger.minor(this, "Could not save to disk: "+data);
						return null;
					}
					block.put("Data", tmp);
				} else {
					Logger.minor(this, "Could not save to disk (not serializable to fieldset): "+data);
					return null;
				}
			}
			if(!block.isEmpty())
				dataFS.put(Integer.toString(i), block);
		}
		fs.put("DataBlocks", dataFS);
		SimpleFieldSet checkFS = new SimpleFieldSet(true);
		checkFS.put("Count", Integer.toString(checkBlocks.length));
		for(int i=0;i<checkBlocks.length;i++) {
			SimpleFieldSet block = new SimpleFieldSet(true);
			if(checkURIs[i] != null)
				block.put("URI", checkURIs[i].toString());
			SingleBlockInserter sbi =
				checkBlockInserters[i];
			// If encoded, then sbi == null => block finished
			boolean finished = encoded && sbi == null;
			if(encoded) {
				block.put("Finished", finished);
			}
			if(!finished) {
				Bucket data = checkBlocks[i];
				if(data != null &&
						data instanceof SerializableToFieldSetBucket) {
					block.put("Data", ((SerializableToFieldSetBucket)data).toFieldSet());
				} else if(encoded) {
					Logger.minor(this, "Could not save to disk (null or not serializable to fieldset): "+data);
					return null;
				}
			}
			if(!block.isEmpty())
				checkFS.put(Integer.toString(i), block);
		}
		fs.put("CheckBlocks", checkFS);
		return fs;
	}
	
	public void start() throws InserterException {
		for(int i=0;i<dataBlockInserters.length;i++) {
			if(dataBlocks[i] != null) { // else already finished on creation
				dataBlockInserters[i] = 
					new SingleBlockInserter(parent.parent, dataBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, CHKBlock.DATA_LENGTH, i, getCHKOnly, false, false, parent.token);
				dataBlockInserters[i].schedule();
			} else {
				parent.parent.completedBlock(true);
			}
		}
		parent.parent.notifyClients();
		started = true;
		if(splitfileAlgo != null && !encoded) {
			// Encode blocks
			Thread t = new Thread(new EncodeBlocksRunnable(), "Blocks encoder");
			t.setDaemon(true);
			t.start();
		} else if(encoded) {
			for(int i=0;i<checkBlockInserters.length;i++) {
				if(checkBlocks[i] == null)
					parent.parent.completedBlock(true);
			}
		}
		if(encoded) {
			parent.encodedSegment(this);
		}
		if(hasURIs) {
			parent.segmentHasURIs(this);
		}
		if(finished) {
			parent.segmentFinished(this);
		}
	}
	
	private class EncodeBlocksRunnable implements Runnable {
		
		public void run() {
			encode();
		}
	}

	void encode() {
		try {
			splitfileAlgo.encode(dataBlocks, checkBlocks, CHKBlock.DATA_LENGTH, blockInsertContext.persistentBucketFactory);
			// Start the inserts
			for(int i=0;i<checkBlockInserters.length;i++) {
				if(checkBlocks[i] != null) { // else already finished on creation
					checkBlockInserters[i] = 
						new SingleBlockInserter(parent.parent, checkBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, CHKBlock.DATA_LENGTH, i + dataBlocks.length, getCHKOnly, false, false, parent.token);
					checkBlockInserters[i].schedule();
				} else {
					parent.parent.completedBlock(true);
				}
			}
			// Tell parent only after have started the inserts.
			// Because of the counting.
			encoded = true;
			parent.encodedSegment(this);
		} catch (IOException e) {
			InserterException ex = 
				new InserterException(InserterException.BUCKET_ERROR, e, null);
			finish(ex);
		} catch (Throwable t) {
			InserterException ex = 
				new InserterException(InserterException.INTERNAL_ERROR, t, null);
			finish(ex);
		}
	}

	private void finish(InserterException ex) {
		synchronized(this) {
			if(finished) return;
			finished = true;
			toThrow = ex;
		}
		parent.segmentFinished(this);
	}

	private void finish() {
		synchronized(this) {
			if(finished) return;
			finished = true;
			toThrow = InserterException.construct(errors);
		}
		parent.segmentFinished(this);
	}
	
	public void onEncode(BaseClientKey key, ClientPutState state) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		int x = sbi.token;
		FreenetURI uri = key.getURI();
		synchronized(this) {
			if(finished) return;
			if(x >= dataBlocks.length) {
				if(checkURIs[x-dataBlocks.length] != null) {
					Logger.normal(this, "Got uri twice for check block "+x+" on "+this);
					return;
				}
				checkURIs[x-dataBlocks.length] = uri;
			} else {
				if(dataURIs[x] != null) {
					Logger.normal(this, "Got uri twice for data block "+x+" on "+this);
					return;
				}
				dataURIs[x] = uri;
			}
			blocksGotURI++;
			if(blocksGotURI != dataBlocks.length + checkBlocks.length) return;
			// Double check
			for(int i=0;i<checkURIs.length;i++) {
				if(checkURIs[i] == null) {
					Logger.error(this, "Check URI "+i+" is null");
					return;
				}
			}
			for(int i=0;i<dataURIs.length;i++) {
				if(dataURIs[i] == null) {
					Logger.error(this, "Data URI "+i+" is null");
					return;
				}
			}
			hasURIs = true;
		}
		parent.segmentHasURIs(this);
	}

	public void onSuccess(ClientPutState state) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		int x = sbi.token;
		if(completed(x)) return;
		finish();
	}

	public void onFailure(InserterException e, ClientPutState state) {
		SingleBlockInserter sbi = (SingleBlockInserter)state;
		int x = sbi.token;
		errors.merge(e);
		if(completed(x)) return;
		finish();
	}

	private synchronized boolean completed(int x) {
		Logger.minor(this, "Completed: "+x+" on "+this+" ( completed="+blocksCompleted+", total="+(dataBlockInserters.length+checkBlockInserters.length));

		if(finished) return true;
		if(x >= dataBlocks.length) {
			x -= dataBlocks.length;
			if(checkBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: check block "+x+" on "+this);
				return true;
			}
			checkBlockInserters[x] = null;
			try {
				parent.ctx.persistentBucketFactory.freeBucket(checkBlocks[x]);
			} catch (IOException e) {
				Logger.error(this, "Could not free "+checkBlocks[x]+" : "+e, e);
			}
			checkBlocks[x] = null;
		} else {
			if(dataBlockInserters[x] == null) {
				Logger.error(this, "Completed twice: data block "+x+" on "+this);
				return true;
			}
			dataBlockInserters[x] = null;
			try {
				parent.ctx.persistentBucketFactory.freeBucket(dataBlocks[x]);
			} catch (IOException e) {
				Logger.error(this, "Could not free "+dataBlocks[x]+" : "+e, e);
			}
			dataBlocks[x] = null;
		}
		blocksCompleted++;
		if(blocksCompleted != dataBlockInserters.length + checkBlockInserters.length) return true;
		return false;
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

	public FreenetURI[] getCheckURIs() {
		return checkURIs;
	}

	public FreenetURI[] getDataURIs() {
		return dataURIs;
	}
	
	InserterException getException() {
		synchronized (this) {
			return toThrow;			
		}
	}

	public void cancel() {
		synchronized(this) {
			if(finished) return;
			finished = true;
			if(toThrow != null)
				toThrow = new InserterException(InserterException.CANCELLED);
		}
		for(int i=0;i<dataBlockInserters.length;i++) {
			SingleBlockInserter sbi = dataBlockInserters[i];
			if(sbi != null)
				sbi.cancel();
		}
		for(int i=0;i<checkBlockInserters.length;i++) {
			SingleBlockInserter sbi = checkBlockInserters[i];
			if(sbi != null)
				sbi.cancel();
		}
		parent.segmentFinished(this);
	}

	public void onTransition(ClientPutState oldState, ClientPutState newState) {
		Logger.error(this, "Illegal transition in SplitFileInserterSegment: "+oldState+" -> "+newState);
	}

	public void onMetadata(Metadata m, ClientPutState state) {
		Logger.error(this, "Got onMetadata from "+state);
	}

	public void onBlockSetFinished(ClientPutState state) {
		// Ignore
		Logger.error(this, "Should not happen: onBlockSetFinished("+state+") on "+this);
	}

	public synchronized boolean hasURIs() {
		return hasURIs;
	}
}
