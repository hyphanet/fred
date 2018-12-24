package freenet.node;

import freenet.io.AddressTracker;
import freenet.io.comm.IncomingPacketFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketHandler;

import java.net.InetAddress;

public class StubUdpSocketHandler implements UdpSocketHandler {

    @Override
    public void setLowLevelFilter(IncomingPacketFilter f) {

    }

    @Override
    public InetAddress getBindTo() {
        return null;
    }

    @Override
    public String getTitle() {
        return null;
    }

    @Override
    public void run() {

    }

    @Override
    public void sendPacket(byte[] blockToSend, Peer destination, boolean allowLocalAddresses) throws Peer.LocalAddressException {

    }

    @Override
    public int getMaxPacketSize() {
        return 0;
    }

    @Override
    public int calculateMaxPacketSize() {
        return 0;
    }

    @Override
    public int getPacketSendThreshold() {
        return 0;
    }

    @Override
    public void start() {

    }

    @Override
    public void close() {

    }

    @Override
    public int getDropProbability() {
        return 0;
    }

    @Override
    public void setDropProbability(int dropProbability) {

    }

    @Override
    public int getPortNumber() {
        return 0;
    }

    @Override
    public int getHeadersLength() {
        return 0;
    }

    @Override
    public int getHeadersLength(Peer peer) {
        return 0;
    }

    @Override
    public AddressTracker getAddressTracker() {
        return null;
    }

    @Override
    public void rescanPortForward() {

    }

    @Override
    public AddressTracker.Status getDetectedConnectivityStatus() {
        return null;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public long getStartTime() {
        return 0;
    }
}
