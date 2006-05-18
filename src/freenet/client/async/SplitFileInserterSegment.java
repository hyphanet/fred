package freenet.client.async;

import java.io.IOException;

import freenet.client.FECCodec;
import freenet.client.FailureCodeTracker;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.client.Metadata;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.Logger;

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
	
	public void start() throws InserterException {
		for(int i=0;i<dataBlockInserters.length;i++) {
			dataBlockInserters[i] = 
				new SingleBlockInserter(parent.parent, dataBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, ClientCHKBlock.DATA_LENGTH, i, getCHKOnly, false, false, parent.token);
			dataBlockInserters[i].schedule();
		}
		if(splitfileAlgo == null) {
			// Don't need to encode blocks
		} else {
			// Encode blocks
			Thread t = new Thread(new EncodeBlocksRunnable(), "Blocks encoder");
			t.setDaemon(true);
			t.start();
		}
	}
	
	private class EncodeBlocksRunnable implements Runnable {
		
		public void run() {
			encode();
		}
	}

	void encode() {
		try {
			splitfileAlgo.encode(dataBlocks, checkBlocks, ClientCHKBlock.DATA_LENGTH, blockInsertContext.bf);
			// Start the inserts
			for(int i=0;i<checkBlockInserters.length;i++) {
				checkBlockInserters[i] = 
					new SingleBlockInserter(parent.parent, checkBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, ClientCHKBlock.DATA_LENGTH, i + dataBlocks.length, getCHKOnly, false, false, parent.token);
				checkBlockInserters[i].schedule();
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
		}
		toThrow = InserterException.construct(errors);
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

	private boolean completed(int x) {
		Logger.minor(this, "Completed: "+x+" on "+this+" ( completed="+blocksCompleted+", total="+(dataBlockInserters.length+checkBlockInserters.length));
		synchronized(this) {
			if(finished) return true;
			if(x >= dataBlocks.length) {
				if(checkBlockInserters[x-dataBlocks.length] == null) {
					Logger.error(this, "Completed twice: check block "+x+" on "+this);
					return true;
				}
				checkBlockInserters[x-dataBlocks.length] = null;
			} else {
				if(dataBlockInserters[x] == null) {
					Logger.error(this, "Completed twice: data block "+x+" on "+this);
					return true;
				}
				dataBlockInserters[x] = null;
			}
			blocksCompleted++;
			if(blocksCompleted != dataBlockInserters.length + checkBlockInserters.length) return true;
			return false;
		}
	}

	public boolean isFinished() {
		return finished;
	}
	
	public boolean isEncoded() {
		return encoded;
	}

	public int countCheckBlocks() {
		return checkBlocks.length;
	}

	public FreenetURI[] getCheckURIs() {
		return checkURIs;
	}

	public FreenetURI[] getDataURIs() {
		return dataURIs;
	}
	
	InserterException getException() {
		return toThrow;
	}

	public void cancel() {
		synchronized(this) {
			if(finished) return;
			finished = true;
		}
		if(toThrow != null)
			toThrow = new InserterException(InserterException.CANCELLED);
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

	public boolean hasURIs() {
		return hasURIs;
	}
}
