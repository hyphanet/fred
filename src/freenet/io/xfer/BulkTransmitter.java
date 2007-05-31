/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.DMT;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.support.BitArray;
import freenet.support.DoubleTokenBucket;

/**
 * Bulk data transfer (not block). Bulk transfer is designed for files which may be much bigger than a 
 * key block, and where we have the whole file at the outset. Do not persist across node restarts.
 * 
 * Used by update over mandatory, sending a file to our peers attached to an N2NTM etc.
 * @author toad
 */
public class BulkTransmitter {

	/** Available blocks */
	final PartiallyReceivedBulk prb;
	/** Peer who we are sending the data to */
	final PeerContext peer;
	/** Transfer UID for messages */
	final long uid;
	/** Blocks we have but haven't sent yet. 0 = block sent or not present, 1 = block present but not sent */
	final BitArray blocksNotSentButPresent;
	private boolean cancelled;
	/** Not persistent over reboots */
	final long peerBootID;
	/** The overall hard bandwidth limiter */
	final DoubleTokenBucket masterThrottle;
	

	public BulkTransmitter(PartiallyReceivedBulk prb, PeerContext peer, long uid, DoubleTokenBucket masterThrottle) {
		this.prb = prb;
		this.peer = peer;
		this.uid = uid;
		this.masterThrottle = masterThrottle;
		peerBootID = peer.getBootID();
		// Need to sync on prb while doing both operations, to avoid race condition.
		// Specifically, we must not get calls to blockReceived() until blocksNotSentButPresent
		// has been set, AND it must be accurate, so there must not be an unlocked period
		// between cloning and adding.
		synchronized(prb) {
			// We can just clone it.
			blocksNotSentButPresent = prb.cloneBlocksReceived();
			prb.add(this);
		}
	}

	/**
	 * Received a block. Set the relevant bit to 1 to indicate that we have the block but haven't sent
	 * it yet. **Only called by PartiallyReceivedBulk.**
	 * @param block The block number that has been received.
	 */
	synchronized void blockReceived(int block) {
		blocksNotSentButPresent.setBit(block, true);
		notifyAll();
	}

	/**
	 * Called when the PRB is aborted.
	 */
	public void onAborted() {
		try {
			peer.sendAsync(DMT.createFNPBulkSendAborted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			// Cool
		}
		synchronized(this) {
			notifyAll();
		}
	}
	
	public void cancel() {
		try {
			peer.sendAsync(DMT.createFNPBulkSendAborted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			// Cool
		}
		synchronized(this) {
			cancelled = true;
			notifyAll();
		}
		prb.remove(this);
	}
	
	class Sender implements Runnable {

		public void run() {
			while(true) {
				if(prb.isAborted()) return;
				int blockNo;
				if(peer.getBootID() != peerBootID) {
					synchronized(this) {
						cancelled = true;
						notifyAll();
					}
					prb.remove(BulkTransmitter.this);
					return;
				}
				boolean hasAll = prb.hasWholeFile();
				synchronized(this) {
					if(cancelled) return;
					blockNo = blocksNotSentButPresent.firstOne();
				}
				if(blockNo < 0 && hasAll) {
					prb.remove(BulkTransmitter.this);
					return; // All done
				}
				else if(blockNo < 0) {
					synchronized(this) {
						try {
							wait(60*1000);
						} catch (InterruptedException e) {
							// No problem
						}
						continue;
					}
				}
				// Send a packet
				byte[] buf = prb.getBlockData(blockNo);
				if(buf == null) {
					// Already cancelled, quit
					return;
				}
				
				// Congestion control and bandwidth limiting
				long now = System.currentTimeMillis();
				long waitUntil = peer.getThrottle().scheduleDelay(now);
				
				masterThrottle.blockingGrab(prb.getPacketSize());
				
				while((now = System.currentTimeMillis()) < waitUntil) {
					long sleepTime = waitUntil - now;
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
				// FIXME should this be reported on bwlimitDelayTime ???
				
				try {
					peer.sendAsync(DMT.createFNPBulkPacketSend(uid, blockNo, buf), null, 0, null);
				} catch (NotConnectedException e) {
					cancel();
					return;
				}
			}
		}
		
	}
}
