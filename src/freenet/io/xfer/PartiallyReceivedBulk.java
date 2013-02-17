/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import java.io.IOException;
import java.util.Arrays;

import freenet.io.comm.MessageCore;
import freenet.io.comm.RetrievalException;
import freenet.support.BitArray;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.RandomAccessThing;

/**
 * Equivalent of PartiallyReceivedBlock, for large(ish) file transfers.
 * As presently implemented, we keep a bitmap in RAM of blocks received, so it should be adequate
 * for fairly large files (128kB for a 1GB file e.g.). We can compress this structure later on if
 * need be.
 * @author toad
 */
public class PartiallyReceivedBulk {
	
	/** The size of the data being received. Does *not* have to be a multiple of blockSize. */
	final long size;
	/** The size of the blocks sent as packets. */
	final int blockSize;
	private final RandomAccessThing raf;
	/** Which blocks have been received and written? */
	private final BitArray blocksReceived;
	final int blocks;
	private BulkTransmitter[] transmitters;
	final MessageCore usm;
	/** The one and only BulkReceiver */
	BulkReceiver recv;
	private int blocksReceivedCount;
	// Abort status
	boolean _aborted;
	int _abortReason;
	String _abortDescription;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/**
	 * Construct a PartiallyReceivedBulk.
	 * @param size Size of the file, does not have to be a multiple of blockSize.
	 * @param blockSize Block size.
	 * @param raf Where to store the data.
	 * @param initialState If true, assume all blocks have been received. If false, assume no blocks have
	 * been received.
	 */
	public PartiallyReceivedBulk(MessageCore usm, long size, int blockSize, RandomAccessThing raf, boolean initialState) {
		this.size = size;
		this.blockSize = blockSize;
		this.raf = raf;
		this.usm = usm;
		long blocks = (size + blockSize - 1) / blockSize;
		if(blocks > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Too big");
		this.blocks = (int)blocks;
		blocksReceived = new BitArray(this.blocks);
		if(initialState) {
			blocksReceived.setAllOnes();
			blocksReceivedCount = this.blocks;
		}
	}

	/**
	 * Clone the blocksReceived BitArray. Used by BulkTransmitter to find what blocks are available on 
	 * creation. BulkTransmitter will have already taken the lock and will keep it over the add() also.
	 * @return A copy of blocksReceived.
	 */
	synchronized BitArray cloneBlocksReceived() {
		return new BitArray(blocksReceived);
	}
	
	/**
	 * Add a BulkTransmitter to the list of BulkTransmitters. When a block comes in, we will tell each
	 * BulkTransmitter about it.
	 * @param bt The BulkTransmitter to register.
	 */
	synchronized void add(BulkTransmitter bt) {
		if(transmitters == null)
			transmitters = new BulkTransmitter[] { bt };
		else {
			transmitters = Arrays.copyOf(transmitters, transmitters.length+1);
			transmitters[transmitters.length-1] = bt;
		}
	}
	
	/**
	 * Called when a block has been received. Will copy the data from the provided buffer and store it.
	 * @param blockNum The block number.
	 * @param data The byte array from which to read the data.
	 * @param offset The start of the data in the buffer.
	 */
	void received(int blockNum, byte[] data, int offset, int length) {
		if(blockNum > blocks) {
			Logger.error(this, "Received block "+blockNum+" of "+blocks+" !");
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Received block "+blockNum);
		BulkTransmitter[] notifyBTs;
		long fileOffset = (long)blockNum * (long)blockSize;
		int bs = (int) Math.min(blockSize, size - fileOffset);
		if(length < bs) {
			String err = "Data too short! Should be "+bs+" actually "+length;
			Logger.error(this, err+" for "+this);
			abort(RetrievalException.PREMATURE_EOF, err);
			return;
		}
		synchronized(this) {
			if(blocksReceived.bitAt(blockNum)) return; // ignore
			blocksReceived.setBit(blockNum, true); // assume the rest of the function succeeds
			blocksReceivedCount++;
			notifyBTs = transmitters;
		}
		try {
			raf.pwrite(fileOffset, data, offset, bs);
		} catch (Throwable t) {
			Logger.error(this, "Failed to store received block "+blockNum+" on "+this+" : "+t, t);
			abort(RetrievalException.IO_ERROR, t.toString());
		}
		if(notifyBTs == null) return;
		for(BulkTransmitter notifyBT: notifyBTs) {
			// Not a generic callback, so no catch{} guard
			notifyBT.blockReceived(blockNum);
		}
	}

	public void abort(int errCode, String why) {
		if(logMINOR)
			Logger.normal(this, "Aborting "+this+": "+errCode+" : "+why+" first missing is "+blocksReceived.firstZero(0), new Exception("debug"));
		BulkTransmitter[] notifyBTs;
		BulkReceiver notifyBR;
		synchronized(this) {
			_aborted = true;
			_abortReason = errCode;
			_abortDescription = why;
			notifyBTs = transmitters;
			notifyBR = recv;
		}
		if(notifyBTs != null) {
			for(BulkTransmitter notifyBT: notifyBTs) {
				notifyBT.onAborted();
			}
		}
		if(notifyBR != null)
			notifyBR.onAborted();
		raf.close();
	}

	public synchronized boolean isAborted() {
		return _aborted;
	}

	public boolean hasWholeFile() {
		return blocksReceivedCount >= blocks;
	}

	public byte[] getBlockData(int blockNum) {
		long fileOffset = (long)blockNum * (long)blockSize;
		int bs = (int) Math.min(blockSize, size - fileOffset);
		byte[] data = new byte[bs];
		try {
			raf.pread(fileOffset, data, 0, bs);
		} catch (IOException e) {
			Logger.error(this, "Failed to read stored block "+blockNum+" on "+this+" : "+e, e);
			abort(RetrievalException.IO_ERROR, e.toString());
			return null;
		}
		return data;
	}

	public synchronized void remove(BulkTransmitter remove) {
		boolean found = false;
		for(BulkTransmitter t: transmitters) {
			if(t == remove) found = true;
		}
		if(!found) return;
		BulkTransmitter[] newTrans = new BulkTransmitter[transmitters.length-1];
		int j = 0;
		for(BulkTransmitter t: transmitters) {
			if(t == remove) continue;
			newTrans[j++] = t;
		}
		transmitters = newTrans;
	}
	
	public int getAbortReason() {
		return _abortReason;
	}
	
	public String getAbortDescription() {
		return _abortDescription;
	}
}
