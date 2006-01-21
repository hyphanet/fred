package freenet.client.async;

import java.io.IOException;

import freenet.client.FECCodec;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;

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
	private InserterException toThrow;
	
	public SplitFileInserterSegment(SplitFileInserter parent, FECCodec splitfileAlgo, Bucket[] origDataBlocks, InserterContext blockInsertContext, boolean getCHKOnly, int segNo) {
		this.parent = parent;
		this.blockInsertContext = blockInsertContext;
		this.splitfileAlgo = splitfileAlgo;
		this.dataBlocks = origDataBlocks;
		int checkBlockCount = splitfileAlgo == null ? 0 : splitfileAlgo.countCheckBlocks();
		checkBlocks = new Bucket[checkBlockCount];
		checkURIs = new FreenetURI[checkBlockCount];
		dataURIs = new FreenetURI[origDataBlocks.length];
		dataBlockInserters = new SingleBlockInserter[dataBlocks.length];
		checkBlockInserters = new SingleBlockInserter[checkBlocks.length];
		this.segNo = segNo;
	}
	
	public void start() {
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
			// Success! Start the fetches.
			encoded = true;
			parent.encodedSegment(this);
			// Start the inserts
			for(int i=0;i<dataBlockInserters.length;i++)
				dataBlockInserters[i] = 
					new SingleBlockInserter(parent.parent, dataBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, ClientCHKBlock.DATA_LENGTH, i);
			for(int i=0;i<checkBlockInserters.length;i++)
				checkBlockInserters[i] = 
					new SingleBlockInserter(parent.parent, checkBlocks[i], (short)-1, FreenetURI.EMPTY_CHK_URI, blockInsertContext, this, false, ClientCHKBlock.DATA_LENGTH, i + dataBlocks.length);
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

	public void onSuccess(ClientPutState state) {
		// TODO Auto-generated method stub
		
	}

	public void onFailure(InserterException e, ClientPutState state) {
		// TODO Auto-generated method stub
		
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
		// TODO Auto-generated method stub
		return null;
	}
	
	public void onEncode(ClientKey key) {
		// TODO Auto-generated method stub
		
	}

}
