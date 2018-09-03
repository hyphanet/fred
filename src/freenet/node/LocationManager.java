package freenet.node;

import freenet.io.comm.Message;

public interface LocationManager {

    int getSwaps();
    int getNoSwaps();
    int getStartedSwaps();
    int getSwapsRejectedAlreadyLocked();
    int getSwapsRejectedNowhereToGo();
    int getSwapsRejectedRateLimit();
    int getSwapsRejectedRecognizedID();

    double getLocation();

    void setLocation(double l);

    void updateLocationChangeSession(double newLoc);

    void start();

    boolean swappingDisabled();

    long getSendSwapInterval();

    boolean handleSwapRequest(Message m, PeerNode pn);

    boolean handleSwapReply(Message m, PeerNode source);

    boolean handleSwapRejected(Message m, PeerNode source);

    boolean handleSwapCommit(Message m, PeerNode source);

    boolean handleSwapComplete(Message m, PeerNode source);

    void clearOldSwapChains();

    void lostOrRestartedNode(PeerNode pn);

    //Return the estimated network size based on locations seen after timestamp or for the whole session if -1
    int getNetworkSizeEstimate(long timestamp);

    Object[] getKnownLocations(long timestamp);

    double getLocChangeSession();

    int getAverageSwapTime();

    void receivedBytes(int x);

    void sentBytes(int x);

    void sentPayload(int x);

    int getNumberOfRemotePeerLocationsSeenInSwaps();
}
