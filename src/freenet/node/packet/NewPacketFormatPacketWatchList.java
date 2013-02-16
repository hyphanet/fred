package freenet.node.packet;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.node.SessionKey;
import freenet.support.Fields;
import freenet.support.Logger;

/** Tracks the encrypted packet numbers for the next several packets.
 * FIXME consider getting rid of all the locking, since we only decode packets for one peer on one thread?
 */
class NewPacketFormatPacketWatchList {
	
	private int highestReceivedSeqNum;

	private byte[][] seqNumWatchList = null;
	/** Index of the packet with the lowest sequence number */
	private int watchListPointer = 0;
	private int watchListOffset = 0;
	
	// FIXME Use a more efficient structure - int[] or maybe just a big byte[].
	// FIXME increase this significantly to let it ride over network interruptions.
	private static final int NUM_SEQNUMS_TO_WATCH_FOR = 1024;
	
	NewPacketFormatPacketWatchList(int theirFirstSeqNum) {
		this.watchListOffset = theirFirstSeqNum;
		
		this.highestReceivedSeqNum = theirFirstSeqNum - 1;
		if(this.highestReceivedSeqNum == -1) this.highestReceivedSeqNum = Integer.MAX_VALUE;
	}
	
	synchronized void receivedPacket(int sequenceNumber) {
		if(NewPacketFormat.seqNumGreaterThan(sequenceNumber, highestReceivedSeqNum, 31)) {
			highestReceivedSeqNum = sequenceNumber;
		}
	}

	synchronized void updateWatchList(SessionKey sessionKey) {
		// Create the watchlist if the key has changed
		if(seqNumWatchList == null) {
			if(NewPacketFormatKeyContext.logMINOR) Logger.minor(this, "Creating watchlist starting at " + watchListOffset);
			
			seqNumWatchList = new byte[NUM_SEQNUMS_TO_WATCH_FOR][4];

			int seqNum = watchListOffset;
			for(int i = 0; i < seqNumWatchList.length; i++) {
				seqNumWatchList[i] = encryptSequenceNumber(seqNum++, sessionKey);
				if(seqNum < 0) seqNum = 0;
			}
		}

		// Move the watchlist if needed
		int highestReceivedSeqNum;
		highestReceivedSeqNum = this.highestReceivedSeqNum;
		// The entry for the highest received sequence number is kept in the middle of the list
		int oldHighestReceived = (int) ((0l + watchListOffset + (seqNumWatchList.length / 2)) % NewPacketFormat.NUM_SEQNUMS);
		if(NewPacketFormat.seqNumGreaterThan(highestReceivedSeqNum, oldHighestReceived, 31)) {
			int moveBy;
			if(highestReceivedSeqNum > oldHighestReceived) {
				moveBy = highestReceivedSeqNum - oldHighestReceived;
			} else {
				moveBy = ((int) (NewPacketFormat.NUM_SEQNUMS - oldHighestReceived)) + highestReceivedSeqNum;
			}

			if(moveBy > seqNumWatchList.length) {
				Logger.warning(this, "Moving watchlist pointer by " + moveBy);
			} else if(moveBy < 0) {
				Logger.warning(this, "Tried moving watchlist pointer by " + moveBy);
				moveBy = 0;
			} else {
				if(NewPacketFormatKeyContext.logDEBUG) Logger.debug(this, "Moving watchlist pointer by " + moveBy);
			}

			int seqNum = (int) ((0l + watchListOffset + seqNumWatchList.length) % NewPacketFormat.NUM_SEQNUMS);
			for(int i = watchListPointer; i < (watchListPointer + moveBy); i++) {
				seqNumWatchList[i % seqNumWatchList.length] = encryptSequenceNumber(seqNum++, sessionKey);
				if(seqNum < 0) seqNum = 0;
			}

			watchListPointer = (watchListPointer + moveBy) % seqNumWatchList.length;
			watchListOffset = (int) ((0l + watchListOffset + moveBy) % NewPacketFormat.NUM_SEQNUMS);
		}
	}

	synchronized int getPossibleMatch(byte[] buf, int offset, int startSequenceNumber) {
		// FIXME optimise. Be careful of modular arithmetic.
		for(int i = 0; i < seqNumWatchList.length; i++) {
			int index = (watchListPointer + i) % seqNumWatchList.length;
			if (!Fields.byteArrayEqual(
						buf, seqNumWatchList[index],
						offset, 0,
						seqNumWatchList[index].length))
				continue;
			int sequenceNumber = (int) ((0l + watchListOffset + i) % NewPacketFormat.NUM_SEQNUMS);
			if(NewPacketFormat.seqNumGreaterThan(sequenceNumber, startSequenceNumber, 31))
				return sequenceNumber;
		}
		return -1;
	}
	
	static byte[] encryptSequenceNumber(int seqNum, SessionKey sessionKey) {
		byte[] seqNumBytes = new byte[4];
		seqNumBytes[0] = (byte) (seqNum >>> 24);
		seqNumBytes[1] = (byte) (seqNum >>> 16);
		seqNumBytes[2] = (byte) (seqNum >>> 8);
		seqNumBytes[3] = (byte) (seqNum);

		BlockCipher ivCipher = sessionKey.ivCipher;

		byte[] IV = new byte[ivCipher.getBlockSize() / 8];
		System.arraycopy(sessionKey.ivNonce, 0, IV, 0, IV.length);
		System.arraycopy(seqNumBytes, 0, IV, IV.length - seqNumBytes.length, seqNumBytes.length);
		ivCipher.encipher(IV, IV);

		PCFBMode cipher = PCFBMode.create(sessionKey.incommingCipher, IV);
		cipher.blockEncipher(seqNumBytes, 0, seqNumBytes.length);

		return seqNumBytes;
	}

}