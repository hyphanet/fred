package freenet.node;

import java.util.Random;

import freenet.io.comm.Message;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.comm.PeerContext;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.TransportPlugin;

/** Base interface for PeerNode, for purposes of the transport layer. Will be overridden
 * for unit tests to simplify testing. 
 * @author toad
 */
public interface BasePeerNode extends PeerContext {

	SessionKey getCurrentKeyTracker(TransportPlugin transportPlugin);

	SessionKey getPreviousKeyTracker(TransportPlugin transportPlugin);

	SessionKey getUnverifiedKeyTracker(TransportPlugin transportPlugin);

	void receivedPacket(boolean dontLog, boolean dataPacket, PacketTransportPlugin transportPlugin);

	void verified(SessionKey s, PacketTransportPlugin transportPlugin);

	void startRekeying(TransportPlugin transportPlugin);

	void maybeRekey(TransportPlugin transportPlugin);

	void reportIncomingPacket(byte[] buf, int offset, int length, long now);

	void reportOutgoingPacket(byte[] data, int offset, int length, long now);
	
	DecodingMessageGroup startProcessingDecryptedMessages(int count);
	
	void reportPing(long rt);

	double averagePingTime();

	void wakeUpSender();

	int getMaxPacketSize(PacketTransportPlugin transportPlugi);

	PeerMessageQueue getMessageQueue();

	boolean shouldPadDataPackets();

	void sendEncryptedPacket(byte[] data, PacketTransportPlugin transportPlugin) throws LocalAddressException;

	void sentPacket(PacketTransportPlugin transportPlugin);

	boolean shouldThrottle();

	void sentThrottledBytes(int length);

	void onNotificationOnlyPacketSent(int length);

	void resentBytes(int bytesToResend);

	Random paddingGen();

	void handleMessage(Message msg);

	/** Make a load stats message.
	 * @param realtime True for the realtime load stats, false for the bulk load stats.
	 * @param highPriority If true, boost the priority so it gets sent fast.
	 * @param noRemember If true, generating it for a lossy message in a packet; don't 
	 * remember that we sent it, since it might be lost, and generate it even if the last 
	 * one was the same, since the last one might be delayed. */
	MessageItem makeLoadStats(boolean realtime, boolean highPriority, boolean noRemember);
	
	boolean grabSendLoadStatsASAP(boolean realtime);

	/** Set the load stats to be sent asap. E.g. if we grabbed it and can't actually 
	 * execute the send for some reason. */
	void setSendLoadStatsASAP(boolean realtime);

	/** Average ping time incorporating variance, calculated like TCP SRTT, as with RFC 2988. */
	double averagePingTimeCorrected();

	/** Double the RTT when we resend a packet. */
	void backoffOnResend();

	/** Report when we received an ack. */
	void receivedAck(long currentTimeMillis, TransportPlugin transportPlugin);

}
