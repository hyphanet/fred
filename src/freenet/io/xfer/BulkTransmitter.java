/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

import freenet.io.comm.AsyncMessageFilterCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.support.BitArray;
import freenet.support.DoubleTokenBucket;
import freenet.support.Logger;
import freenet.support.transport.ip.IPUtil;

/**
 * Bulk data transfer (not block). Bulk transfer is designed for files which may be much bigger than a 
 * key block, and where we have the whole file at the outset. Do not persist across node restarts.
 * 
 * Used by update over mandatory, sending a file to our peers attached to an N2NTM etc.
 * @author toad
 */
public class BulkTransmitter {

	/** If no packets sent in this period, and no completion acknowledgement / cancellation, assume failure. */
	static final int TIMEOUT = 5*60*1000;
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
	private boolean sentCancel;
	private boolean finished;
	final int packetSize;
	
	public BulkTransmitter(PartiallyReceivedBulk prb, PeerContext peer, long uid, DoubleTokenBucket masterThrottle) throws DisconnectedException {
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
		try {
			prb.usm.addAsyncFilter(MessageFilter.create().setNoTimeout().setSource(peer).setType(DMT.FNPBulkReceiveAborted).setField(DMT.UID, uid),
					new AsyncMessageFilterCallback() {
						public void onMatched(Message m) {
							cancel();
						}
						public boolean shouldTimeout() {
							synchronized(BulkTransmitter.this) {
								if(cancelled || finished) return true;
							}
							if(BulkTransmitter.this.prb.isAborted()) return true;
							return false;
						}
			});
			prb.usm.addAsyncFilter(MessageFilter.create().setNoTimeout().setSource(peer).setType(DMT.FNPBulkReceivedAll).setField(DMT.UID, uid),
					new AsyncMessageFilterCallback() {
						public void onMatched(Message m) {
							completed();
						}
						public boolean shouldTimeout() {
							synchronized(BulkTransmitter.this) {
								if(cancelled || finished) return true;
							}
							if(BulkTransmitter.this.prb.isAborted()) return true;
							return false;
						}
			});
		} catch (DisconnectedException e) {
			cancel();
			throw e;
		}
		packetSize = DMT.bulkPacketTransmitSize(prb.blockSize) +
			peer.getOutgoingMangler().fullHeadersLengthOneMessage();
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
		sendAbortedMessage();
		synchronized(this) {
			notifyAll();
		}
	}
	
	private void sendAbortedMessage() {
		synchronized(this) {
			if(sentCancel) return;
			sentCancel = true;
		}
		try {
			peer.sendAsync(DMT.createFNPBulkSendAborted(uid), null, 0, null);
		} catch (NotConnectedException e) {
			// Cool
		}
	}

	public void cancel() {
		sendAbortedMessage();
		synchronized(this) {
			cancelled = true;
			notifyAll();
		}
		prb.remove(this);
	}

	/** Like cancel(), but without the negative overtones: The client says it's got everything,
	 * we believe them (even if we haven't sent everything; maybe they had a partial). */
	public void completed() {
		synchronized(this) {
			finished = true;
			notifyAll();
		}
		prb.remove(this);
	}
	
	/**
	 * Send the file.
	 * @return True if the file was successfully sent. False otherwise.
	 */
	public boolean send() {
		long lastSentPacket = System.currentTimeMillis();
		while(true) {
			if(prb.isAborted()) return false;
			int blockNo;
			if(peer.getBootID() != peerBootID) {
				synchronized(this) {
					cancelled = true;
					notifyAll();
				}
				prb.remove(BulkTransmitter.this);
				return false;
			}
			synchronized(this) {
				if(finished) return true;
				if(cancelled) return false;
				blockNo = blocksNotSentButPresent.firstOne();
			}
			if(blockNo < 0) {
				// Wait for a packet, BulkReceivedAll or BulkReceiveAborted
				synchronized(this) {
					try {
						wait(60*1000);
					} catch (InterruptedException e) {
						// No problem
						continue;
					}
				}
				long end = System.currentTimeMillis();
				if(end - lastSentPacket > TIMEOUT) {
					Logger.error(this, "Send timed out on "+this);
					cancel();
					return false;
				}
			}
			// Send a packet
			byte[] buf = prb.getBlockData(blockNo);
			if(buf == null) {
				// Already cancelled, quit
				return false;
			}
			
			// Congestion control and bandwidth limiting
			long now = System.currentTimeMillis();
			long waitUntil = peer.getThrottle().scheduleDelay(now);
			
			if(IPUtil.isValidAddress(peer.getPeer().getAddress(), false))
				masterThrottle.blockingGrab(packetSize);
			
			while((now = System.currentTimeMillis()) < waitUntil) {
				long sleepTime = waitUntil - now;
				try {
					synchronized(this) {
						wait(sleepTime);
						if(finished) {
							masterThrottle.recycle(packetSize);
							return true;
						}
						if(cancelled) {
							masterThrottle.recycle(packetSize);
							return false;
						}
					}
				} catch (InterruptedException e) {
					// Ignore
				}
			}
			// FIXME should this be reported on bwlimitDelayTime ???
			
			try {
				peer.sendAsync(DMT.createFNPBulkPacketSend(uid, blockNo, buf), null, 0, null);
				synchronized(this) {
					blocksNotSentButPresent.setBit(blockNo, false);
				}
				lastSentPacket = System.currentTimeMillis();
			} catch (NotConnectedException e) {
				cancel();
				return false;
			}
		}
	}
	
}
