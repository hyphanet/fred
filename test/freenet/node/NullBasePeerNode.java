package freenet.node;

import java.lang.ref.WeakReference;
import java.util.Random;

import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerRestartedException;
import freenet.io.comm.SocketHandler;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.xfer.PacketThrottle;
import freenet.io.xfer.WaitedTooLongException;

/** Tests can override this to record specific events e.g. rekey */
public class NullBasePeerNode implements BasePeerNode {

	public Peer getPeer() {
		return null;
	}

	public void forceDisconnect(boolean dump) {
		throw new UnsupportedOperationException();
	}

	public boolean isConnected() {
		return true;
	}

	public boolean isRoutable() {
		return false;
	}

	public int getVersionNumber() {
		throw new UnsupportedOperationException();
	}

	public MessageItem sendAsync(Message msg, AsyncMessageCallback cb,
			ByteCounter ctr) throws NotConnectedException {
		throw new UnsupportedOperationException();
	}

	public MessageItem sendThrottledMessage(Message msg, int packetSize,
			ByteCounter ctr, int timeout, boolean waitForSent,
			AsyncMessageCallback callback) throws NotConnectedException,
			WaitedTooLongException, SyncSendWaitedTooLongException,
			PeerRestartedException {
		throw new UnsupportedOperationException();
	}

	public long getBootID() {
		return 0;
	}

	public PacketThrottle getThrottle() {
		return null;
	}

	public SocketHandler getSocketHandler() {
		return null;
	}

	public OutgoingPacketMangler getOutgoingMangler() {
		return null;
	}

	public WeakReference<? extends PeerContext> getWeakRef() {
		return new WeakReference<NullBasePeerNode>(this);
	}

	public String shortToString() {
		return toString();
	}

	public void transferFailed(String reason) {
		// Awww
	}

	public boolean unqueueMessage(MessageItem item) {
		throw new UnsupportedOperationException();
	}
	
	SessionKey currentKey;
	SessionKey previousKey;
	SessionKey unverifiedKey;

	public SessionKey getCurrentKeyTracker() {
		return currentKey;
	}

	public SessionKey getPreviousKeyTracker() {
		return previousKey;
	}

	public SessionKey getUnverifiedKeyTracker() {
		return unverifiedKey;
	}

	public void receivedPacket(boolean dontLog, boolean dataPacket) {
		// Do nothing by default
	}

	public void verified(SessionKey s) {
		throw new UnsupportedOperationException();
	}

	public void startRekeying() {
		throw new UnsupportedOperationException();
	}

	public void maybeRekey() {
		// Do nothing
	}

	public void reportIncomingPacket(byte[] buf, int offset, int length,
			long now) {
		// Ignore
	}

	public void reportOutgoingPacket(byte[] data, int offset, int length,
			long now) {
		// Ignore
	}

	public void processDecryptedMessage(byte[] data, int offset, int length,
			int overhead) {
		throw new UnsupportedOperationException();
	}

	public void reportPing(long rt) {
		// Ignore
	}

	public double averagePingTime() {
		return 250;
	}

	public void wakeUpSender() {
		// Do nothing
	}

	public int getMaxPacketSize() {
		return 1280;
	}

	public PeerMessageQueue getMessageQueue() {
		return null;
	}

	public boolean shouldPadDataPackets() {
		return false;
	}

	public void sendEncryptedPacket(byte[] data) throws LocalAddressException {
		// Do nothing
	}

	public void sentPacket() {
		// Do nothing
	}

	public boolean shouldThrottle() {
		return false;
	}

	public void sentThrottledBytes(int length) {
		// Do nothing
	}

	public void onNotificationOnlyPacketSent(int length) {
		// Do nothing
	}

	public void dumpTracker(SessionKey brokenKey) {
		// Do nothing as we are not tracking sessionkeys, the test is probably passing them in.
		// Will be overridden by some tests.
	}

	public void resentBytes(int bytesToResend) {
		// Ignore
	}

	public Random paddingGen() {
		return null;
	}

}
