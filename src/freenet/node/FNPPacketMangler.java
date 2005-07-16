package freenet.node;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.BlockCipher;
import freenet.crypt.PCFBMode;
import freenet.io.comm.DMT;
import freenet.io.comm.LowLevelFilter;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketManager;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Encodes and decodes packets for FNP.
 * 
 * This includes encryption, authentication, and may later
 * include queueing etc. (that may require some interface
 * changes in LowLevelFilter).
 */
public class FNPPacketMangler implements LowLevelFilter {

    final Node node;
    final PeerManager pm;
    final UdpSocketManager usm;
    static final int MAX_PACKETS_IN_FLIGHT = 256; 
    
    public FNPPacketMangler(Node node) {
        this.node = node;
        this.pm = node.peers;
        this.usm = node.usm;
    }

    /**
     * Packet format:
     *
     * E_session_ecb(
     *         4 bytes:  sequence number XOR first 4 bytes of node identity
     *         12 bytes: first 12 bytes of H(data)
     *         )
     * E_session_ecb(
     *         16 bytes: bytes 12-28 of H(data)
     *         ) XOR previous ciphertext XOR previous plaintext
     * 4 bytes: bytes 28-32 of H(data) XOR bytes 0-4 of H(data)
     * E_session_pcfb(data) // IV = first 32 bytes of packet
     * 
     */
    
    /**
     * Decrypt and authenticate packet.
     * Then feed it to USM.checkFilters.
     * Packets generated should have a NodePeer on them.
     */
    public void process(byte[] buf, int offset, int length, Peer peer) {
        if(length < 41) {
            Logger.error(this, "Packet from "+peer+" too short "+length+" bytes");
        }
        
        /**
         * Look up the Peer.
         * If we know it, check the packet with that key.
         * Otherwise try all of them (on the theory that nodes 
         * occasionally change their IP addresses).
         */
        NodePeer pn = pm.getByPeer(peer);
        if(pn != null) {
            if(tryProcess(buf, offset, length, pn)) return;
        }
        // FIXME: trying all peers, should try only connected peers
        // FIXME: this is because we aren't doing key negotiation yet
        // FIXME: we are simply assigning a symmetric key to a node
        // FIXME: note that this provides no real security
        // FIXME: but it lets us get the infrastructure largely right
        for(int i=0;i<pm.myPeers.length;i++) {
            pn = pm.myPeers[i];
            if(tryProcess(buf, offset, length, pn)) return;
        }
        Logger.error(this,"Unmatchable packet from "+peer);
    }

    /**
     * Try to process an incoming packet with a given PeerNode.
     * We need to know where the packet has come from in order to
     * decrypt and authenticate it.
     * @param payloadHash The hash of the packet payload section. We can
     * check this against the encrypted hash.
     */
    private boolean tryProcess(byte[] buf, int offset, int length, NodePeer pn) {
        /** First block:
         * 
         * E_session_ecb(
         *         4 bytes:  sequence number XOR first 4 bytes of node identity
         *         12 bytes: first 12 bytes of H(data)
         * )
         */
        BlockCipher sessionCipher = pn.getSessionCipher();
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize()*2)
            throw new IllegalStateException("Block size must be half key size");
        byte[] temp = new byte[blockSize];
        System.arraycopy(buf, offset, temp, 0, blockSize);
        sessionCipher.decipher(temp, temp);
        int seqNumber = (((temp[3] & 0xff) << 8 + (temp[2] & 0xff)) << 8 + 
                (temp[1] & 0xff)) << 8 + (temp[0] & 0xff);
        int partNodeFingerprint = pn.getNodeFingerprintInt();
        seqNumber ^= partNodeFingerprint;
        // Now is it credible?
        // As long as it's within +/- 256, this is valid.
        int targetSeqNumber = pn.lastReceivedSequenceNumber();
        if(Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT)
            return false;
        
        // Plausible, so...
        
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        byte[] hashBuf = new byte[md.getDigestLength()];
        if(md.getDigestLength() != 2*blockSize)
            throw new IllegalStateException("Block size must be half digest length!");
        
        // Copy the first part of the hash into the buffer
        System.arraycopy(temp, 4, hashBuf, 0, temp.length-4);
        int filledTo = temp.length-4;
        
        // Second block is PCBC'd. I.e. after encryption we XORed it
        // with the ciphertext AND the plaintext of the first block.
        byte[] xor = new byte[blockSize];
        // Put the plaintext in
        System.arraycopy(temp, 0, xor, 0, blockSize);
        // Now xor with the ciphertext
        for(int i=0;i<blockSize;i++)
            xor[i] ^= buf[offset+i];
        // Now extract the ciphertext of the second block
        for(int i=0;i<blockSize;i++)
            xor[i] ^= buf[offset+i+blockSize];
        // Now decode the second block
        sessionCipher.decipher(xor, xor);
        // Now fill in the next chunk of the hash
        System.arraycopy(xor, 0, hashBuf, filledTo, blockSize);
        // Almost full - 1 hash = 2 blocks, so we're 4 bytes short
        
        // Now extract the last 4 bytes
        for(int i=0;i<4;i++)
            hashBuf[filledTo+blockSize+i] = 
                (byte) (buf[offset+i+(blockSize*2)] ^ hashBuf[i]);
        
        // Yay, we have the full hash
        
        // So lets decrypt the data
        
        byte[] decrypted = new byte[length - (blockSize*2 + 4)];
        
        System.arraycopy(buf, offset+(blockSize*2)+4, decrypted, 0, length-((blockSize*2)+4));
        PCFBMode pcfb;
        byte[] iv = new byte[blockSize];
        System.arraycopy(buf, offset, iv, 0, blockSize*2);
        pcfb = new PCFBMode(sessionCipher,iv);
        pcfb.blockDecipher(decrypted, 0, decrypted.length);
        
        // Decrypted the data, yay
        
        // Check the hash
        byte[] hash = md.digest(decrypted);
        if(!java.util.Arrays.equals(hash, hashBuf)) {
            Logger.error(this, "Incorrect hash for packet probably from "+pn);
            return false;
        }
        
        // Lots more to do yet!
        processDecryptedData(decrypted, seqNumber, pn);
        return true;
    }

    /**
     * Process an incoming packet, once it has been decrypted.
     * @param decrypted
     * @param seqNumber
     * @param pn
     */
    private void processDecryptedData(byte[] decrypted, int seqNumber, NodePeer pn) {
        /**
         * Decoded format:
         * 1 byte - version number (0)
         * 1 byte - number of acknowledgements
         * Acknowledgements:
         * 1 byte - ack (+ve integer, subtract from seq-1) to get seq# to ack
         * 
         * 1 byte - number of explicit retransmit requests
         * Explicit retransmit requests:
         * 1 byte - retransmit request (+ve integer, subtract from seq-1) to get seq# to resend
         * 
         * 1 byte - number of packets forgotten
         * Forgotten packets:
         * 1 byte - forgotten packet seq# (+ve integer, subtract from seq-1) to get seq# lost
         * 
         * 1 byte - number of messages
         * 2 bytes - message length
         * first message
         * 2 bytes - second message length
         * second message
         * ...
         * last message
         * anything beyond this point is padding, to be ignored
         */ 
        
        // Use ptr to simplify code
        int ptr = 0;
        
        int version = decrypted[ptr++];
        if(version != 0) {
            Logger.error(this,"Packet from "+pn+" decrypted but invalid version: "+version);
            return;
        }
        
        pn.receivedPacket(seqNumber);
        
        int ackCount = decrypted[ptr] & 0xff;
        Logger.minor(this, "Acks: "+ackCount);
        
        for(int i=0;i<ackCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            int realSeqNo = seqNumber - (1 + offset);
            pn.acknowledgedPacket(realSeqNo);
        }
        
        int retransmitCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Retransmit requests: "+retransmitCount);
        
        for(int i=0;i<retransmitCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            int realSeqNo = seqNumber - (1 + offset);
            pn.resendPacket(realSeqNo);
        }
        
        int forgottenCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Forgotten packets: "+forgottenCount);
        
        for(int i=0;i<forgottenCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            int realSeqNo = seqNumber - (1 + offset);
            pn.destForgotPacket(realSeqNo);
        }
        
        int messages = decrypted[ptr++] & 0xff;
        
        for(int i=0;i<messages;i++) {
            int length = ((decrypted[ptr++] & 0xff) << 8) +
            	(decrypted[ptr++] & 0xff);
            if(length > decrypted.length - ptr) {
                Logger.error(this, "Message longer than remaining space: "+length);
                return;
            }
            Message m = usm.decodePacket(decrypted, ptr, length, pn.getPeer());
            if(m != null) {
                m.set(DMT.FNP_SOURCE_PEERNODE, pn);
                Logger.minor(this, "Dispatching packet: "+m);
                usm.checkFilters(m);
            }
        }
    }

    public byte[] processOutgoing(byte[] buf, int offset, int length, Peer peer) {
        // TODO Auto-generated method stub
        return null;
    }

}
