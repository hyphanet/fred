package freenet.node;

import freenet.crypt.BlockCipher;
import freenet.io.AddressTracker;
import freenet.io.comm.FreenetInetAddress;
import freenet.support.SimpleFieldSet;

import java.io.IOException;
import java.security.interfaces.ECPublicKey;

public interface NodeCrypto {

    int IDENTITY_LENGTH = 32;

    void readCrypto(SimpleFieldSet fs) throws IOException;

    void initCrypto();

    void start();

    SimpleFieldSet exportPrivateFieldSet();

    SimpleFieldSet exportPublicFieldSet();

    byte[] myCompressedSetupRef();

    byte[] myCompressedHeavySetupRef();

    byte[] myCompressedFullRef();

    ECPublicKey getECDSAP256Pubkey();

    void onSetDropProbability(int val);

    void stop();

    PeerNode[] getPeerNodes();

    boolean allowConnection(PeerNode pn, FreenetInetAddress addr);

    void maybeBootConnection(PeerNode peerNode,
                             FreenetInetAddress address);

    BlockCipher getAnonSetupCipher();

    PeerNode[] getAnonSetupPeerNodes();

    byte[] getIdentity(int negType);

    boolean definitelyPortForwarded();

    AddressTracker.Status getDetectedConnectivityStatus();

    FreenetInetAddress getBindTo();

    boolean wantAnonAuth();

    boolean wantAnonAuthChangeIP();
}
