package freenet.node;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.crypt.BlockCipher;
import freenet.crypt.EntropySource;
import freenet.crypt.PCFBMode;
import freenet.io.comm.DMT;
import freenet.io.comm.LowLevelFilter;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
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
    static final EntropySource fnpTimingSource = new EntropySource();
    static final EntropySource myPacketDataSource = new EntropySource();
    static final int RANDOM_BYTES_LENGTH = 12;
    
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
     * Note that the buffer can be modified by this method.
     */
    public void process(byte[] buf, int offset, int length, Peer peer) {
        node.random.acceptTimerEntropy(fnpTimingSource, 0.25);
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
        /**
         * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
         * 
         * So first two blocks are the hash, PCBC encoded (meaning the
         * first one is ECB, and the second one is ECB XORed with the 
         * ciphertext and plaintext of the first block).
         */
        BlockCipher sessionCipher = pn.getSessionCipher();
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize()*2)
            throw new IllegalStateException("Block size must be half key size");
        
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        int digestLength = md.getDigestLength();
        
        if(digestLength != 2*blockSize)
            throw new IllegalStateException("Block size must be half digest length!");

        byte[] packetHash = new byte[digestLength];
        System.arraycopy(buf, offset, packetHash, 0, digestLength);
        
        // Decrypt the sequence number and see if it's plausible
        // Verify the hash later
        
        PCFBMode pcfb;
        pcfb = new PCFBMode(sessionCipher);
        // Set IV to the hash, after it is encrypted
        pcfb.reset(packetHash);
        
        pcfb.blockDecipher(buf, offset+digestLength, 4);
        
        int seqNumber = (((buf[offset+digestLength+3] & 0xff) << 8
                + (buf[offset+digestLength+2] & 0xff)) << 8 + 
                (buf[offset+digestLength+1] & 0xff)) << 8 +
                (buf[offset+digestLength] & 0xff);
        
        // Now is it credible?
        // As long as it's within +/- 256, this is valid.
        int targetSeqNumber = pn.lastReceivedSequenceNumber();
        if(Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT)
            return false;
        
        // Plausible, so lets decrypt the rest of the data
        
        pcfb.blockDecipher(buf, offset+digestLength+4,
                length-(offset+digestLength+4));
        
        md.update(buf, offset+digestLength, length-
                (offset+digestLength));
        byte[] realHash = md.digest();

        // Now decrypt the original hash
        
        // First block
        byte[] temp = new byte[blockSize];
        System.arraycopy(buf, offset, temp, 0, blockSize);
        sessionCipher.decipher(temp, temp);
        System.arraycopy(temp, 0, packetHash, 0, blockSize);
        
        // Second block
        System.arraycopy(buf, offset+blockSize, temp, 0, blockSize);
        // Un-PCBC
        for(int i=0;i<blockSize;i++) {
            temp[i] ^= (buf[offset+i] ^ packetHash[i]);
        }
        sessionCipher.decipher(temp, temp);
        System.arraycopy(temp, 0, packetHash, blockSize, blockSize);
        
        // Check the hash
        if(!java.util.Arrays.equals(packetHash, realHash)) {
            Logger.error(this, "Packet possibly from "+pn+" hash does not match");
            return false;
        }
        
        // We are finished with the original buffer
        // So we can use it for temp space
        for(int i=0;i<md.getDigestLength();i++) {
            buf[offset+i] ^= packetHash[i];
        }
        node.random.acceptEntropyBytes(myPacketDataSource, buf, offset, md.getDigestLength(), 0.5);
        
        byte[] decrypted = new byte[length-digestLength];
        System.arraycopy(buf, offset+digestLength, decrypted, 0, length-digestLength);
        
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
        int ptr = RANDOM_BYTES_LENGTH;
        
        int version = decrypted[ptr++];
        if(ptr > decrypted.length) {
            Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            return;
        }
        if(version != 0) {
            Logger.error(this,"Packet from "+pn+" decrypted but invalid version: "+version);
            return;
        }
        
        pn.receivedPacket(seqNumber);
        
        int ackCount = decrypted[ptr] & 0xff;
        Logger.minor(this, "Acks: "+ackCount);
        
        for(int i=0;i<ackCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
                return;
            }
            int realSeqNo = seqNumber - (1 + offset);
            pn.acknowledgedPacket(realSeqNo);
        }
        
        int retransmitCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Retransmit requests: "+retransmitCount);
        
        for(int i=0;i<retransmitCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int realSeqNo = seqNumber - (1 + offset);
            pn.resendPacket(realSeqNo);
        }
        
        int forgottenCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Forgotten packets: "+forgottenCount);
        
        for(int i=0;i<forgottenCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int realSeqNo = seqNumber - (1 + offset);
            pn.destForgotPacket(realSeqNo);
        }
        
        int messages = decrypted[ptr++] & 0xff;
        
        for(int i=0;i<messages;i++) {
            if(ptr+1 > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int length = ((decrypted[ptr++] & 0xff) << 8) +
            	(decrypted[ptr++] & 0xff);
            if(length > decrypted.length - ptr) {
                Logger.error(this, "Message longer than remaining space: "+length);
                return;
            }
            Message m = usm.decodePacket(decrypted, ptr, length, pn);
            if(m != null) {
                m.set(DMT.FNP_SOURCE_PEERNODE, pn);
                Logger.minor(this, "Dispatching packet: "+m);
                usm.checkFilters(m);
            }
        }
    }

    /**
     * Build a packet and send it. From a Message recently converted into byte[],
     * but with no outer formatting.
     */
    public void processOutgoing(byte[] buf, int offset, int length, PeerContext peer) {
        byte[] newBuf = new byte[length+3];
        newBuf[0] = 1;
        newBuf[1] = (byte)(length >> 8);
        newBuf[2] = (byte)length;
        System.arraycopy(buf, offset, newBuf, 3, length);
        processOutgoingPreformatted(newBuf, 0, newBuf.length, peer);
    }

    /**
     * Encrypt a packet, prepend packet acks and packet resend requests, and send it. 
     * The provided data is ready-formatted, meaning that it already has the message 
     * length's and message counts.
     * @param buf Buffer to read data from.
     * @param offset Point at which to start reading.
     * @param length Number of bytes to read.
     * @param peer Peer to send the message to.
     */
    public void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerContext peer) {
        if(!(peer instanceof NodePeer))
            throw new ClassCastException();
        NodePeer pn = (NodePeer) peer;
        // Allocate a sequence number
        int seqNumber = pn.allocateOutgoingPacketNumber();
        
        int[] acks = pn.grabAcks();
        int[] resendRequests = pn.grabResendRequests();
        // We do not support forgotten packets at present
        
        int packetLength = acks.length + resendRequests.length + 3 + 1 + length + 4 + RANDOM_BYTES_LENGTH;

        byte[] plaintext = new byte[packetLength];
        
        int ptr = 0;

        plaintext[ptr++] = (byte)(seqNumber >> 24);
        plaintext[ptr++] = (byte)(seqNumber >> 16);
        plaintext[ptr++] = (byte)(seqNumber >> 8);
        plaintext[ptr++] = (byte)seqNumber;
        
        byte[] randomJunk = new byte[RANDOM_BYTES_LENGTH];
        
        node.random.nextBytes(randomJunk);
        System.arraycopy(randomJunk, 0, plaintext, ptr, RANDOM_BYTES_LENGTH);
        ptr += RANDOM_BYTES_LENGTH;
        
        plaintext[ptr++] = 0; // version number
        plaintext[ptr++] = (byte) acks.length;
        for(int i=0;i<acks.length;i++) {
            int ackSeq = acks[i];
            int offsetSeq = seqNumber - (1 + ackSeq);
            if(offsetSeq > 255 || offsetSeq < 0)
                throw new IllegalStateException("bad ack offset "+offsetSeq);
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        plaintext[ptr++] = (byte) resendRequests.length;
        for(int i=0;i<resendRequests.length;i++) {
            int reqSeq = acks[i];
            int offsetSeq = seqNumber - (1 + reqSeq);
            if(offsetSeq > 255 || offsetSeq < 0)
                throw new IllegalStateException("bad resend request offset "+offsetSeq);
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        // No forgotten packets
        plaintext[ptr++] = 0;

        System.arraycopy(buf, offset, plaintext, ptr, length);
        
        processOutgoingFullyFormatted(plaintext, pn);

        byte[] saveable = new byte[length];
        System.arraycopy(buf, offset, saveable, 0, length);
        pn.sentPacket(saveable, seqNumber);
    }

    /**
     * Encrypt and send a packet.
     * @param plaintext The packet's plaintext, including all formatting,
     * including acks and resend requests. Is clobbered.
     */
    private void processOutgoingFullyFormatted(byte[] plaintext, NodePeer pn) {
        BlockCipher sessionCipher = pn.getSessionCipher();
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize()*2)
            throw new IllegalStateException("Block size must be half key size");
        
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        int digestLength = md.getDigestLength();
        
        if(digestLength != 2*blockSize)
            throw new IllegalStateException("Block size must be half digest length!");
        
        byte[] output = new byte[plaintext.length + digestLength];
        
        md.update(plaintext);
        
        byte[] digestTemp;
        
        digestTemp = md.digest();

        // Put plaintext in output
        System.arraycopy(digestTemp, 0, output, 0, digestLength);
        
        // Encrypt digestTemp
        sessionCipher.encipher(digestTemp, digestTemp);
        
        // The first block is exactly as it should be
        // The second block gets XORed with the ciphertext and
        // the plaintext
        
        for(int i=0;i<blockSize;i++)
            digestTemp[blockSize+i] ^=
                (output[i] /* plaintext */ ^ digestTemp[i] /* ciphetext */);
        
        // Now copy it back
        System.arraycopy(digestTemp, 0, output, 0, digestLength);
        // Yay, we have an encrypted hash
        
        PCFBMode pcfb = new PCFBMode(sessionCipher, digestTemp);
        pcfb.blockEncipher(output, digestLength, plaintext.length);
        
        // We have a packet
        // Send it
        
        usm.sendPacket(output, pn.getPeer());
    }

}
