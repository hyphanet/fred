package freenet.support.transport.ip;

import freenet.support.Executor;

import java.net.InetAddress;

public interface IPAddressDetector extends Runnable {
    String getCheckpointName();

    long nextCheckpoint();

    InetAddress[] getAddressNoCallback();

    InetAddress[] getAddress(Executor executor);

    void clearCached();
}
