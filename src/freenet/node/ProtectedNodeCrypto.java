package freenet.node;

import freenet.io.comm.UdpSocketHandler;
import freenet.keys.InsertableClientSSK;
import freenet.support.SimpleFieldSet;

interface ProtectedNodeCrypto extends NodeCrypto {

    byte[] ecdsaSign(byte[]... data);

    void setPortForwardingBroken();

    SimpleFieldSet exportPublicFieldSet(boolean forSetup, boolean forAnonInitiator, boolean forARK);

    int getPortNumber();

    boolean isOpennet();

    FNPPacketMangler getPacketMangler();

    UdpSocketHandler getSocket();

    byte[] getEcdsaPubKeyHash();

    NodeCryptoConfig getConfig();

    byte[] getIdentityHash();

    byte[] getIdentityHashHash();

    byte[] getMyIdentity();

    long getMyARKNumber();

    void setMyARKNumber(long l);

    InsertableClientSSK getMyARK();

    NodeIPPortDetector getDetector();
}
