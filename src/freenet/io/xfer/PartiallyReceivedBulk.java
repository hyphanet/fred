/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.support.BitArray;
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
	final RandomAccessThing raf;
	/** Which blocks have been received and written? */
	final BitArray blocksReceived;
	final int blocks;
	
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
}
