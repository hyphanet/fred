package freenet.node;

import java.util.List;

import freenet.io.comm.Peer;

public class DummyPacketFormat implements PacketFormat {

    @Override
    public boolean handleReceivedPacket(byte[] buf, int offset, int length, long now, Peer replyTo) {
        throw new IllegalStateException();
    }

    @Override
    public boolean maybeSendPacket(long now, boolean ackOnly) throws BlockedTooLongException {
        return false;
    }

    @Override
    public List<MessageItem> onDisconnect() {
        return null;
    }

    @Override
    public boolean canSend(SessionKey key) {
        return false;
    }

    @Override
    public long timeNextUrgent(boolean canSend, long now) {
        return Long.MAX_VALUE;
    }

    @Override
    public long timeSendAcks() {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean fullPacketQueued(int maxPacketSize) {
        return false;
    }

    @Override
    public void checkForLostPackets() {
        // Ignore.
    }

    @Override
    public long timeCheckForLostPackets() {
        return Long.MAX_VALUE;
    }

}
