/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.support.BitArray;
import freenet.support.Logger;
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
	/** The one and only BulkReceiver */
	private BulkReceiver recv;
	
	/**
	 * Construct a PartiallyReceivedBulk.
	 * @param size Size of the file, does not have to be a multiple of blockSize.
	 * @param blockSize Block size.
	 * @param raf Where to store the data.
	 * @param initialState If true, assume all blocks have been received. If false, assume no blocks have
	 * been received.
	 */
	public PartiallyReceivedBulk(long size, int blockSize, RandomAccessThing raf, boolean initialState) {
		this.size = size;
		this.blockSize = blockSize;
		this.raf = raf;
		long blocks = size / blockSize + (size % blockSize > 0 ? 1 : 0);
		if(blocks > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Too big");
		this.blocks = (int)blocks;
		blocksReceived = new BitArray(this.blocks);
		if(initialState)
			blocksReceived.setAllOnes();
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
			BulkTransmitter[] t = new BulkTransmitter[transmitters.length+1];
			System.arraycopy(transmitters, 0, t, 0, transmitters.length);
			t[transmitters.length] = bt;
			transmitters = t;
		}
	}
	
	/**
	 * Called when a block has been received. Will copy the data from the provided buffer and store it.
	 * @param blockNum The block number.
	 * @param data The byte array from which to read the data.
	 * @param offset The start of the 
	 */
	void received(int blockNum, byte[] data, int offset) {
		BulkTransmitter[] notifyBTs;
		synchronized(this) {
			if(blocksReceived.bitAt(blockNum)) return; // ignore
			blocksReceived.setBit(blockNum, true); // assume the rest of the function succeeds
			notifyBTs = transmitters;
		}
		try {
			long fileOffset = (long)blockNum * (long)blockSize;
			int bs = (int) Math.max(blockSize, size - fileOffset);
			raf.pwrite(fileOffset, data, offset, bs);
		} catch (Throwable t) {
			fail(t);
		}
		if(notifyBTs == null) return;
		for(int i=0;i<notifyBTs.length;i++) {
			// Not a generic callback, so no catch{} guard
			notifyBTs[i].blockReceived(blockNum);
		}
	}

	/**
	 * Fail the transfer because of an unrecoverable exception e.g. an error in storing the data.
	 * @param t The throwable causing this failure.
	 */
	private void fail(Throwable t) {
		Logger.error(this, "Failing transfer: "+this+" : "+t, t);
		BulkTransmitter[] notifyBTs;
		synchronized(this) {
			notifyBTs = transmitters;
		}
		if(notifyBTs != null) {
			for(int i=0;i<notifyBTs.length;i++)
				notifyBTs[i].fail(t);
		}
		if(recv != null)
			recv.fail(t);
	}
}
