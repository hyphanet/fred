package freenet.client.async;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Vector;

import com.onionnetworks.util.FileUtil;

import freenet.client.FailureCodeTracker;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.keys.SSKBlock;
import freenet.node.LowLevelPutException;
import freenet.node.SimpleSendableInsert;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public class BinaryBlobInserter implements ClientPutState {

	final ClientPutter parent;
	final Object clientContext;
	final MySendableInsert[] inserters;
	final FailureCodeTracker errors;
	final int maxRetries;
	final int consecutiveRNFsCountAsSuccess;
	private boolean logMINOR;
	private int completedBlocks;
	private int succeededBlocks;
	private boolean fatal;
	
	BinaryBlobInserter(Bucket blob, ClientPutter parent, Object clientContext, boolean tolerant, short prioClass, InsertContext ctx) 
	throws IOException, BinaryBlobFormatException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		this.maxRetries = ctx.maxInsertRetries;
		this.consecutiveRNFsCountAsSuccess = ctx.consecutiveRNFsCountAsSuccess;
		this.parent = parent;
		this.clientContext = clientContext;
		this.errors = new FailureCodeTracker(true);
		Vector myInserters = new Vector();
		DataInputStream dis = new DataInputStream(blob.getInputStream());
		long magic = dis.readLong();
		if(magic != BinaryBlob.BINARY_BLOB_MAGIC)
			throw new BinaryBlobFormatException("Bad magic");
		short version = dis.readShort();
		if(version != BinaryBlob.BINARY_BLOB_OVERALL_VERSION)
			throw new BinaryBlobFormatException("Unknown overall version");
		
		int i=0;
		while(true) {
			long blobLength;
			try {
				blobLength = dis.readInt() & 0xFFFFFFFFL;
			} catch (EOFException e) {
				// End of file
				dis.close();
				break;
			}
			short blobType = dis.readShort();
			short blobVer = dis.readShort();
			
			if(blobType == BinaryBlob.BLOB_END) {
				dis.close();
				break;
			} else if(blobType == BinaryBlob.BLOB_BLOCK) {
				if(blobVer != BinaryBlob.BLOB_BLOCK_VERSION)
					// Even if tolerant, if we can't read a blob there probably isn't much we can do.
					throw new BinaryBlobFormatException("Unknown block blob version");
				if(blobLength < 9)
					throw new BinaryBlobFormatException("Block blob too short");
				short keyType = dis.readShort();
				int keyLen = dis.readUnsignedByte();
				short headersLen = dis.readShort();
				short dataLen = dis.readShort();
				short pubkeyLen = dis.readShort();
				if(blobLength != 9 + keyLen + headersLen + dataLen + pubkeyLen)
					throw new BinaryBlobFormatException("Binary blob too short for data lengths");
				byte[] keyBytes = new byte[keyLen];
				byte[] headersBytes = new byte[headersLen];
				byte[] dataBytes = new byte[dataLen];
				byte[] pubkeyBytes = new byte[pubkeyLen];
				dis.readFully(keyBytes);
				dis.readFully(headersBytes);
				dis.readFully(dataBytes);
				dis.readFully(pubkeyBytes);
				KeyBlock block;
				try {
					block = Key.createBlock(keyType, keyBytes, headersBytes, dataBytes, pubkeyBytes);
				} catch (KeyVerifyException e) {
					throw new BinaryBlobFormatException("Invalid key: "+e.getMessage(), e);
				}
				
				MySendableInsert inserter =
					new MySendableInsert(i, block, prioClass, getScheduler(block), clientContext);
				
				myInserters.add(inserter);
				
			} else {
				if(tolerant) {
					FileUtil.skipFully(dis, blobLength);
				} else {
					throw new BinaryBlobFormatException("Unknown blob type: "+blobType);
				}
			}
			i++;
		}
		inserters = (MySendableInsert[]) myInserters.toArray(new MySendableInsert[myInserters.size()]);
		parent.addMustSucceedBlocks(inserters.length);
	}
	
	private ClientRequestScheduler getScheduler(KeyBlock block) {
		if(block instanceof CHKBlock)
			return parent.chkScheduler;
		else if(block instanceof SSKBlock)
			return parent.sskScheduler;
		else throw new IllegalArgumentException("Unknown block type "+block.getClass()+" : "+block);
	}

	public void cancel() {
		for(int i=0;i<inserters.length;i++) {
			if(inserters[i] != null)
				inserters[i].cancel();
		}
		parent.onFailure(new InsertException(InsertException.CANCELLED), this);
	}

	public BaseClientPutter getParent() {
		return parent;
	}

	public SimpleFieldSet getProgressFieldset() {
		// FIXME not supported
		return null;
	}

	public Object getToken() {
		return clientContext;
	}

	public void schedule() throws InsertException {
		for(int i=0;i<inserters.length;i++) {
			inserters[i].schedule();
		}
	}
	
	class MySendableInsert extends SimpleSendableInsert {

		final int blockNum;
		private int consecutiveRNFs;
		private int retries;
		
		public MySendableInsert(int i, KeyBlock block, short prioClass, ClientRequestScheduler scheduler, Object client) {
			super(block, prioClass, client, scheduler);
			this.blockNum = i;
		}
		
		public void onSuccess() {
			synchronized(this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				succeededBlocks++;
			}
			parent.completedBlock(false);
			maybeFinish();
		}

		// FIXME duplicated code from SingleBlockInserter
		// FIXME combine it somehow
		public void onFailure(LowLevelPutException e) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
			}
			if(parent.isCancelled()) {
				fail(new InsertException(InsertException.CANCELLED), true);
				return;
			}
			logMINOR = Logger.shouldLog(Logger.MINOR, BinaryBlobInserter.this);
			switch(e.code) {
			case LowLevelPutException.COLLISION:
				fail(new InsertException(InsertException.COLLISION), false);
				break;
			case LowLevelPutException.INTERNAL_ERROR:
				errors.inc(InsertException.INTERNAL_ERROR);
				break;
			case LowLevelPutException.REJECTED_OVERLOAD:
				errors.inc(InsertException.REJECTED_OVERLOAD);
				break;
			case LowLevelPutException.ROUTE_NOT_FOUND:
				errors.inc(InsertException.ROUTE_NOT_FOUND);
				break;
			case LowLevelPutException.ROUTE_REALLY_NOT_FOUND:
				errors.inc(InsertException.ROUTE_REALLY_NOT_FOUND);
				break;
			default:
				Logger.error(this, "Unknown LowLevelPutException code: "+e.code);
				errors.inc(InsertException.INTERNAL_ERROR);
			}
			if(e.code == LowLevelPutException.ROUTE_NOT_FOUND) {
				consecutiveRNFs++;
				if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" / "+consecutiveRNFsCountAsSuccess);
				if(consecutiveRNFs == consecutiveRNFsCountAsSuccess) {
					if(logMINOR) Logger.minor(this, "Consecutive RNFs: "+consecutiveRNFs+" - counting as success");
					onSuccess();
					return;
				}
			} else
				consecutiveRNFs = 0;
			if(logMINOR) Logger.minor(this, "Failed: "+e);
			retries++;
			if((retries > maxRetries) && (maxRetries != -1)) {
				fail(InsertException.construct(errors), false);
				return;
			}
			schedule();
		}

		private void fail(InsertException e, boolean fatal) {
			synchronized(BinaryBlobInserter.this) {
				if(inserters[blockNum] == null) return;
				inserters[blockNum] = null;
				completedBlocks++;
				if(fatal) BinaryBlobInserter.this.fatal = true;
			}
			if(fatal)
				parent.fatallyFailedBlock();
			else
				parent.failedBlock();
			maybeFinish();
		}
	}

	public void maybeFinish() {
		boolean success;
		boolean wasFatal;
		synchronized(this) {
			if(completedBlocks != inserters.length)
				return;
			success = completedBlocks == succeededBlocks;
			wasFatal = fatal;
		}
		if(success) {
			parent.onSuccess(this);
		} else if(wasFatal)
			parent.onFailure(new InsertException(InsertException.FATAL_ERRORS_IN_BLOCKS, errors, null), this);
		else
			parent.onFailure(new InsertException(InsertException.TOO_MANY_RETRIES_IN_BLOCKS, errors, null), this);
	}
	
}
