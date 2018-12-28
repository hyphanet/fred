package freenet.node;

import freenet.crypt.BlockCipher;
import freenet.io.AddressTracker;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.UdpSocketHandler;
import freenet.keys.InsertableClientSSK;
import freenet.support.SimpleFieldSet;

import java.io.IOException;
import java.security.interfaces.ECPublicKey;

public class StubNodeCrypto implements ProtectedNodeCrypto {

    @Override
    public byte[] ecdsaSign(byte[]... data) {
        return new byte[0];
    }

    @Override
    public void setPortForwardingBroken() {

    }

    @Override
    public SimpleFieldSet exportPublicFieldSet(boolean forSetup, boolean forAnonInitiator, boolean forARK) {
        return null;
    }

    @Override
    public int getPortNumber() {
        return 0;
    }

    @Override
    public boolean isOpennet() {
        return false;
    }

    @Override
    public FNPPacketMangler getPacketMangler() {
        return null;
    }

    @Override
    public UdpSocketHandler getSocket() {
        return null;
    }

    @Override
    public byte[] getEcdsaPubKeyHash() {
        return new byte[0];
    }

    @Override
    public NodeCryptoConfig getConfig() {
        return null;
    }

    @Override
    public byte[] getIdentityHash() {
        return new byte[0];
    }

    @Override
    public byte[] getIdentityHashHash() {
        return new byte[0];
    }

    @Override
    public byte[] getMyIdentity() {
        return new byte[0];
    }

    @Override
    public long getMyARKNumber() {
        return 0;
    }

    @Override
    public void setMyARKNumber(long l) {

    }

    @Override
    public InsertableClientSSK getMyARK() {
        return null;
    }

    @Override
    public NodeIPPortDetector getDetector() {
        return null;
    }

    @Override
    public void readCrypto(SimpleFieldSet fs) throws IOException {

    }

    @Override
    public void initCrypto() {

    }

    @Override
    public void start() {

    }

    @Override
    public SimpleFieldSet exportPrivateFieldSet() {
        return null;
    }

    @Override
    public SimpleFieldSet exportPublicFieldSet() {
        return null;
    }

    @Override
    public byte[] myCompressedSetupRef() {
        return new byte[0];
    }

    @Override
    public byte[] myCompressedHeavySetupRef() {
        return new byte[0];
    }

    @Override
    public byte[] myCompressedFullRef() {
        return new byte[0];
    }

    @Override
    public ECPublicKey getECDSAP256Pubkey() {
        return null;
    }

    @Override
    public void onSetDropProbability(int val) {

    }

    @Override
    public void stop() {

    }

    @Override
    public PeerNode[] getPeerNodes() {
        return new PeerNode[0];
    }

    @Override
    public boolean allowConnection(PeerNode pn, FreenetInetAddress addr) {
        return false;
    }

    @Override
    public void maybeBootConnection(PeerNode peerNode, FreenetInetAddress address) {

    }

    @Override
    public BlockCipher getAnonSetupCipher() {
        return null;
    }

    @Override
    public PeerNode[] getAnonSetupPeerNodes() {
        return new PeerNode[0];
    }

    @Override
    public byte[] getIdentity(int negType) {
        return new byte[0];
    }

    @Override
    public boolean definitelyPortForwarded() {
        return false;
    }

    @Override
    public AddressTracker.Status getDetectedConnectivityStatus() {
        return null;
    }

    @Override
    public FreenetInetAddress getBindTo() {
        return null;
    }

    @Override
    public boolean wantAnonAuth() {
        return false;
    }

    @Override
    public boolean wantAnonAuthChangeIP() {
        return false;
    }
}
