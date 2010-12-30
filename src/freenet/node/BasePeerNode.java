package freenet.node;

import java.util.Random;

import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.comm.PeerContext;

/** Base interface for PeerNode, for purposes of the transport layer. Will be overridden
 * for unit tests to simplify testing. 
 * @author toad
 */
interface BasePeerNode extends PeerContext {

	SessionKey getCurrentKeyTracker();

	SessionKey getPreviousKeyTracker();

	SessionKey getUnverifiedKeyTracker();

	void receivedPacket(boolean dontLog, boolean dataPacket);

	void verified(SessionKey s);

	void startRekeying();

	void maybeRekey();

	void reportIncomingPacket(byte[] buf, int offset, int length, long now);

	void reportOutgoingPacket(byte[] data, int offset, int length, long now);
	
	void processDecryptedMessage(byte[] data, int offset, int length, int overhead);

	void reportPing(long rt);

	double averagePingTime();

	void wakeUpSender();

	int getMaxPacketSize();

	PeerMessageQueue getMessageQueue();

	boolean shouldPadDataPackets();

	void sendDecryptedPacket(byte[] data) throws LocalAddressException;

	void sentPacket();

	boolean shouldThrottle();

	void sentThrottledBytes(int length);

	void onNotificationOnlyPacketSent(int length);

	void dumpTracker(SessionKey brokenKey);

	void resentBytes(int bytesToResend);

	Random paddingGen();

}
