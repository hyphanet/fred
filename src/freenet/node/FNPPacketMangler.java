package freenet.node;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import freenet.crypt.BlockCipher;
import freenet.crypt.EntropySource;
import freenet.crypt.PCFBMode;
import freenet.io.comm.LowLevelFilter;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
import freenet.support.HexUtil;
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
    final int HASH_LENGTH;
    
    public FNPPacketMangler(Node node) {
        this.node = node;
        this.pm = node.peers;
        this.usm = node.usm;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        HASH_LENGTH = md.getDigestLength();
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
        Logger.minor(this, "Packet length "+length);

        /**
         * Look up the Peer.
         * If we know it, check the packet with that key.
         * Otherwise try all of them (on the theory that nodes 
         * occasionally change their IP addresses).
         */
        NodePeer opn = pm.getByPeer(peer);
        NodePeer pn;
        
        if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
            
            if(opn != null) {
                if(tryProcess(buf, offset, length, opn)) return;
            }
            // FIXME: trying all peers, should try only connected peers
            // FIXME: this is because we aren't doing key negotiation yet
            // FIXME: we are simply assigning a symmetric key to a node
            // FIXME: note that this provides no real security
            // FIXME: but it lets us get the infrastructure largely right
            for(int i=0;i<pm.myPeers.length;i++) {
                pn = pm.myPeers[i];
                if(pn == opn) continue;
                if(tryProcess(buf, offset, length, pn)) {
                    // IP address change
                    pn.changedIP(peer);
                    return;
                }
            }
        }

        // If it doesn't match as a data packet, maybe it's a handshake packet?
        if(length < HASH_LENGTH) {
            Logger.error(this,"Unmatchable packet from "+peer+" - not matched, and too short to be phase 1 handshake");
            return;
        }

        MessageDigest md = getDigest();
        
        if(length >= HASH_LENGTH + HANDSHAKE_RANDOM_LENGTH) {
            
            // Try phase 1
            
            byte[] random = new byte[HANDSHAKE_RANDOM_LENGTH];
            System.arraycopy(buf, 0, random, 0, HANDSHAKE_RANDOM_LENGTH);
            byte[] targetHash = new byte[HASH_LENGTH];
            System.arraycopy(buf, HANDSHAKE_RANDOM_LENGTH, targetHash, 0, HASH_LENGTH);
            md.update(random);
            md.update(node.myIdentity);
            byte[] X = md.digest();
            Logger.minor(this, "X="+HexUtil.bytesToHex(X));
        
            // Now just try to find a node such that H(X XOR H(alice ID)) == targetHash
            
            if(opn != null) {
                if(tryProcessHandshakePhase1(X, targetHash, opn, md)) {
                    sendHandshakeReply(opn, random, peer, md);
                    return;
                }
            }
            
            // Try *all* peers
            for(int i=0;i<pm.myPeers.length;i++) {
                pn = pm.myPeers[i];
                if(pn == pm.myPeers[i]) continue;
                if(tryProcessHandshakePhase1(X, targetHash, pn, md)) {
                    // IP address change; phase 2 will notify
                    sendHandshakeReply(opn, random, peer, md);
                    return;
                }
            }
        }
        
        if(length >= HASH_LENGTH) {
            // Phase 2
            if(opn != null) {
                if(tryProcessHandshakePhase2(buf, offset, length, opn, peer, md))
                    // Will set IP, seq# etc
                    return;
                // Try *all* peers
                for(int i=0;i<pm.myPeers.length;i++) {
                    pn = pm.myPeers[i];
                    if(pn == pm.myPeers[i]) continue;
                    if(tryProcessHandshakePhase2(buf, offset, length, opn, peer, md))
                        return;
                }
            }
        }
        
        // Phase 2?
            
        Logger.error(this,"Unmatchable packet from "+peer);
    }

    private boolean tryProcessHandshakePhase2(byte[] buf, int offset, int length, NodePeer opn, Peer peer, MessageDigest md) {
        // Decrypt hash first
        byte[] decryptedHash = new byte[HASH_LENGTH];
        System.arraycopy(buf, offset, decryptedHash, 0, HASH_LENGTH);
        opn.sessionCipher.decipher(decryptedHash, decryptedHash);
        // Now try to reproduce it
        byte[][] lastSentRandoms = opn.getLastSentHandshakeRandoms();
        for(int i=0;i<lastSentRandoms.length;i++) {
            byte[] r = lastSentRandoms[i];
            if(r == null) continue;
            md.update(r);
            md.update(opn.getNodeIdentity());
            md.update(node.myIdentity);
            if(Arrays.equals(md.digest(), decryptedHash)) {
                // Successful handshake!
                opn.handshakeSucceeded();
                opn.changedIP(peer);
                return true;
            }
        }
        // Didn't match
        return false;
    }

    /**
     * Send a handshake reply.
     */
    private void sendHandshakeReply(NodePeer opn, byte[] random, Peer peer, MessageDigest md) {
        Logger.normal(this, "Sending handshake reply to "+peer+" for "+opn);
        md.update(random);
        md.update(node.myIdentity);
        md.update(opn.getNodeIdentity());
        byte[] result = md.digest();
        opn.sessionCipher.encipher(result, result);
        usm.sendPacket(result, peer);
        opn.maybeShouldSendHandshake();
    }

    /**
     * Create a new SHA-256 MessageDigest
     */
    private MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
    }

    /**
     * Does H(X + (Alice's ID)) == targetHash? If so, send a
     * handshake reply.
     * @param opn The node to check
     * @param md A cleared MessageDigest to use.
     */
    private boolean tryProcessHandshakePhase1(byte[] x, byte[] targetHash, NodePeer opn, MessageDigest md) {
        Logger.normal(this, "Processing handshake phase 1 possibly from "+opn);
        md.update(x);
        md.update(opn.getNodeIdentity());
        if(Arrays.equals(md.digest(), targetHash)) {
            Logger.normal(this, "Was definitely from "+opn);
            return true;
        }
        Logger.normal(this, "Not from "+opn);
        return false;
    }

    /**
     * Send a handshake packet to a node.
     */
    public void sendHandshake(NodePeer pn) {
        Logger.normal(this, "Sending handshake packet to "+pn);
        MessageDigest md = getDigest();
        byte[] random = new byte[HANDSHAKE_RANDOM_LENGTH];
        node.random.nextBytes(random);
        md.update(random);
        md.update(pn.getNodeIdentity());
        byte[] temp = md.digest();
        Logger.minor(this, "Sending X="+HexUtil.bytesToHex(temp));
        md.update(temp);
        md.update(node.myIdentity);
        byte[] sendPacket = new byte[HANDSHAKE_RANDOM_LENGTH + HASH_LENGTH];
        System.arraycopy(random, 0, sendPacket, 0, random.length);
        try {
            md.digest(sendPacket, random.length, HASH_LENGTH);
        } catch (DigestException e) {
            throw new Error(e);
        }
        pn.sentHandshakeRandom(random);
        usm.sendPacket(sendPacket, pn.getPeer());
    }

    static final int HANDSHAKE_RANDOM_LENGTH = 12;
    
    /*
     * Packet format:
     * Phase 1:
     * Alice -> Bob: R_alice H(H(R_alice + (Bob's ID) ) + (Alice's ID) )
     * Phase 2:
     * Alice -> Bob: E_session ( R_alice + R_bob + seq# + <4 bytes random> )
     * Either can have any amount of padding.
     */
    

    /**
     * Try to process an incoming packet with a given PeerNode.
     * We need to know where the packet has come from in order to
     * decrypt and authenticate it.
     */
    private boolean tryProcess(byte[] buf, int offset, int length, NodePeer pn) {
        Logger.minor(this,"Entering tryProcess: "+buf+","+offset+","+length+","+pn);
        /**
         * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
         * 
         * So first two blocks are the hash, PCBC encoded (meaning the
         * first one is ECB, and the second one is ECB XORed with the 
         * ciphertext and plaintext of the first block).
         */
        BlockCipher sessionCipher = pn.getSessionCipher();
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize())
            throw new IllegalStateException("Block size must be equal to key size");
        
        if(HASH_LENGTH != blockSize)
            throw new IllegalStateException("Block size must be digest length!");

        byte[] packetHash = new byte[HASH_LENGTH];
        System.arraycopy(buf, offset, packetHash, 0, HASH_LENGTH);
        
        // Decrypt the sequence number and see if it's plausible
        // Verify the hash later
        
        PCFBMode pcfb;
        pcfb = new PCFBMode(sessionCipher);
        // Set IV to the hash, after it is encrypted
        pcfb.reset(packetHash);
        //Logger.minor(this,"IV:\n"+HexUtil.bytesToHex(packetHash));
        
        byte[] seqBuf = new byte[4];
        System.arraycopy(buf, offset+HASH_LENGTH, seqBuf, 0, 4);
        //Logger.minor(this, "Encypted sequence number: "+HexUtil.bytesToHex(seqBuf));
        pcfb.blockDecipher(seqBuf, 0, 4);
        //Logger.minor(this, "Decrypted sequence number: "+HexUtil.bytesToHex(seqBuf));
        
        int seqNumber = ((((((seqBuf[0] & 0xff) << 8)
                + (seqBuf[1] & 0xff)) << 8) + 
                (seqBuf[2] & 0xff)) << 8) +
                (seqBuf[3] & 0xff);

        int targetSeqNumber = pn.lastReceivedSequenceNumber();
        Logger.minor(this, "Target seq: "+targetSeqNumber);
        Logger.minor(this, "Sequence number: "+seqNumber+"="+Integer.toHexString(seqNumber));

        if(seqNumber == -1) {
            // Ack/resendreq-only packet
        } else {
            // Now is it credible?
            // As long as it's within +/- 256, this is valid.
            if(targetSeqNumber != -1 && Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT)
                return false;
        }
        Logger.minor(this, "Sequence number received: "+seqNumber);
        
        // Plausible, so lets decrypt the rest of the data
        
        byte[] plaintext = new byte[length-(4+HASH_LENGTH)];
        System.arraycopy(buf, offset+HASH_LENGTH+4, plaintext, 0, length-(HASH_LENGTH+4));
        
        pcfb.blockDecipher(plaintext, 0, length-(HASH_LENGTH+4));
        
        //Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));
        
        MessageDigest md = getDigest();
        md.update(seqBuf);
        md.update(plaintext);
        byte[] realHash = md.digest();

        // Now decrypt the original hash
        
        byte[] temp = new byte[blockSize];
        System.arraycopy(buf, offset, temp, 0, blockSize);
        sessionCipher.decipher(temp, temp);
        System.arraycopy(temp, 0, packetHash, 0, blockSize);
        
        // Check the hash
        if(!java.util.Arrays.equals(packetHash, realHash)) {
            Logger.minor(this, "Packet possibly from "+pn+" hash does not match:\npacketHash="+
                    HexUtil.bytesToHex(packetHash)+"\n  realHash="+HexUtil.bytesToHex(realHash)+" ("+(length-HASH_LENGTH)+" bytes payload)");
            return false;
        }
        
        if(seqNumber != -1 && pn.alreadyReceived(seqNumber)) {
            pn.queueAck(seqNumber);
            Logger.normal(this, "Received packet twice: "+seqNumber);
            return true;
        }
        
        for(int i=0;i<md.getDigestLength();i++) {
            packetHash[i] ^= buf[offset+i];
        }
        node.random.acceptEntropyBytes(myPacketDataSource, packetHash, 0, md.getDigestLength(), 0.5);
        
        // Lots more to do yet!
        processDecryptedData(plaintext, seqNumber, pn);
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

        /** Highest sequence number sent - not the same as this packet's seq number */
        int realSeqNumber = seqNumber;
        
        if(seqNumber == -1) {
            if(ptr+4 > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
                return;
            }
            realSeqNumber =
                ((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
                        (decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
            ptr+=4;
        } else {
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
                return;
            }
            realSeqNumber = seqNumber + (decrypted[ptr++] & 0xff);
        }
        
        //Logger.minor(this, "Reference seq number: "+HexUtil.bytesToHex(decrypted, ptr, 4));
        
        if(ptr+4 > decrypted.length) {
            Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            return;
        }
        int referenceSeqNumber = 
            ((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
                    (decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
        ptr+=4;
        
        Logger.minor(this, "Reference sequence number: "+referenceSeqNumber);

        // No sequence number == don't ack
        if(seqNumber != -1)
            pn.receivedPacket(seqNumber);
        
        int ackCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Acks: "+ackCount);
        
        for(int i=0;i<ackCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
                return;
            }
            int realSeqNo = referenceSeqNumber - offset;
            Logger.minor(this, "ACK: "+realSeqNo);
            pn.acknowledgedPacket(realSeqNo);
        }
        
        int retransmitCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Retransmit requests: "+retransmitCount);
        
        for(int i=0;i<retransmitCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int realSeqNo = referenceSeqNumber - offset;
            Logger.minor(this, "RetransmitRequest: "+realSeqNo);
            pn.resendPacket(realSeqNo);
        }

        int ackRequestsCount = decrypted[ptr++] & 0xff;
        
        // These two are relative to our outgoing packet number
        // Because they relate to packets we have sent.
        for(int i=0;i<ackRequestsCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int realSeqNo = realSeqNumber - offset;
            Logger.minor(this, "AckRequest: "+realSeqNo);
            pn.receivedAckRequest(realSeqNo);
        }
        
        int forgottenCount = decrypted[ptr++] & 0xff;
        //Logger.minor(this, "Forgotten packets: "+forgottenCount);
        
        for(int i=0;i<forgottenCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+pn);
            }
            int realSeqNo = realSeqNumber - offset;
            pn.destForgotPacket(realSeqNo);
        }

        if(seqNumber == -1) return;
        // No sequence number == no messages
        
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
            ptr+=length;
            if(m != null) {
                //Logger.minor(this, "Dispatching packet: "+m);
                usm.checkFilters(m);
            }
        }
    }

    /**
     * Build a packet and send it. From a Message recently converted into byte[],
     * but with no outer formatting.
     */
    public void processOutgoing(byte[] buf, int offset, int length, PeerContext peer) {
        byte[] newBuf = preformat(buf, offset, length, peer);
        processOutgoingPreformatted(newBuf, 0, newBuf.length, peer);
    }

    public byte[] preformat(Message msg, NodePeer pn) {
        byte[] buf = msg.encodeToPacket(this, pn);
        return preformat(buf, 0, buf.length, pn);
    }
    
    public byte[] preformat(byte[] buf, int offset, int length, PeerContext peer) {
        byte[] newBuf;
        if(buf != null) {
            newBuf = new byte[length+3];
            newBuf[0] = 1;
            newBuf[1] = (byte)(length >> 8);
            newBuf[2] = (byte)length;
            System.arraycopy(buf, offset, newBuf, 3, length);
        } else {
            newBuf = new byte[1];
            newBuf[0] = 0;
        }
        return newBuf;
    }
    
    public void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerContext peer) {
        processOutgoingPreformatted(buf, offset, length, peer, -1);
    }
    
    /**
     * Encrypt a packet, prepend packet acks and packet resend requests, and send it. 
     * The provided data is ready-formatted, meaning that it already has the message 
     * length's and message counts.
     * @param buf Buffer to read data from.
     * @param offset Point at which to start reading.
     * @param length Number of bytes to read.
     * @param peer Peer to send the message to.
     * @param packetNumber If specified, force use of this particular packet number.
     * Means this is a resend of a dropped packet.
     */
    public void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerContext peer, int packetNumber) {
        if(!(peer instanceof NodePeer)) {
            Logger.error(this, "Fed non-NodePeer PeerContext: "+peer);
            throw new ClassCastException();
        }
        NodePeer pn = (NodePeer) peer;
        
        if(!pn.isConnected()) {
            // Drop it
            // FIXME: queue? I don't think we can at this level... Queue at the processOutgoing level?
            Logger.normal(this, "Dropping packet: Not connected yet");
            return;
        }
        
        // We do not support forgotten packets at present
        
        // Allocate a sequence number
        int seqNumber;
        if(packetNumber > 0)
            seqNumber = packetNumber;
        else {
            if(buf.length == 1)
                // Ack/resendreq only packet
                seqNumber = -1;
            else
                seqNumber = pn.allocateOutgoingPacketNumber();
        }
        
        Logger.minor(this, "Sequence number (sending): "+seqNumber+" ("+packetNumber+") to "+pn.getPeer());
        
        int[] acks = pn.grabAcks();
        int[] resendRequests = pn.grabResendRequests();
        int[] ackRequests = pn.grabAckRequests();
        
        int packetLength = acks.length + resendRequests.length + ackRequests.length + 4 + 1 + length + 4 + 4 + RANDOM_BYTES_LENGTH;
        if(packetNumber == -1) packetLength += 4;
        else packetLength++;

        byte[] plaintext = new byte[packetLength];
        
        byte[] randomJunk = new byte[RANDOM_BYTES_LENGTH];
        
        int ptr = offset;

        plaintext[ptr++] = (byte)(seqNumber >> 24);
        plaintext[ptr++] = (byte)(seqNumber >> 16);
        plaintext[ptr++] = (byte)(seqNumber >> 8);
        plaintext[ptr++] = (byte)seqNumber;
        
        node.random.nextBytes(randomJunk);
        System.arraycopy(randomJunk, 0, plaintext, ptr, RANDOM_BYTES_LENGTH);
        ptr += RANDOM_BYTES_LENGTH;
        
        plaintext[ptr++] = 0; // version number

        /** The last sent sequence number, so that we can refer to packets
         * sent after this packet was originally sent (it may be a resend) */
        int realSeqNumber = seqNumber;
        
        if(seqNumber == -1) {
            realSeqNumber = pn.getLastOutgoingSeqNumber();
            plaintext[ptr++] = (byte)(realSeqNumber >> 24);
            plaintext[ptr++] = (byte)(realSeqNumber >> 16);
            plaintext[ptr++] = (byte)(realSeqNumber >> 8);
            plaintext[ptr++] = (byte)realSeqNumber;
        } else {
            realSeqNumber = pn.getLastOutgoingSeqNumber();
            plaintext[ptr++] = (byte)(realSeqNumber - seqNumber);
        }
        
        int otherSideSeqNumber = pn.lastReceivedSequenceNumber();
        Logger.minor(this, "otherSideSeqNumber: "+otherSideSeqNumber);
        
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 24);
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 16);
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 8);
        plaintext[ptr++] = (byte)otherSideSeqNumber;
        
        plaintext[ptr++] = (byte) acks.length;
        for(int i=0;i<acks.length;i++) {
            int ackSeq = acks[i];
            Logger.minor(this, "Acking "+ackSeq);
            int offsetSeq = otherSideSeqNumber - ackSeq;
            if(offsetSeq > 255 || offsetSeq < 0)
                throw new IllegalStateException("bad ack offset "+offsetSeq+
                        " - seqNumber="+otherSideSeqNumber+", ackNumber="+ackSeq);
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        plaintext[ptr++] = (byte) resendRequests.length;
        for(int i=0;i<resendRequests.length;i++) {
            int reqSeq = resendRequests[i];
            Logger.minor(this, "Resend req: "+reqSeq);
            int offsetSeq = otherSideSeqNumber - reqSeq;
            if(offsetSeq > 255 || offsetSeq < 0)
                throw new IllegalStateException("bad resend request offset "+offsetSeq+
                        " - reqSeq="+reqSeq+", otherSideSeqNumber="+otherSideSeqNumber);
            plaintext[ptr++] = (byte)offsetSeq;
        }

        plaintext[ptr++] = (byte) ackRequests.length;
        Logger.minor(this, "Ackrequests: "+ackRequests.length);
        for(int i=0;i<ackRequests.length;i++) {
            int ackReqSeq = ackRequests[i];
            Logger.minor(this, "Ack request "+i+": "+ackReqSeq);
            // Relative to packetNumber - we are asking them to ack
            // a packet we sent to them.
            int offsetSeq = realSeqNumber - ackReqSeq;
            if(offsetSeq > 255 || offsetSeq < 0)
                throw new IllegalStateException("bad ack requests offset: "+offsetSeq+
                        " - ackReqSeq="+ackReqSeq+", packetNumber="+packetNumber);
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        // No forgotten packets
        plaintext[ptr++] = 0;

        System.arraycopy(buf, offset, plaintext, ptr, length);

        if(seqNumber != -1) {
            byte[] saveable = new byte[length];
            System.arraycopy(buf, offset, saveable, 0, length);
            pn.sentPacket(saveable, seqNumber);
        }
        
        Logger.minor(this, "Sending...");

        processOutgoingFullyFormatted(plaintext, pn);
        Logger.minor(this, "Sent packet");
    }

    /**
     * Encrypt and send a packet.
     * @param plaintext The packet's plaintext, including all formatting,
     * including acks and resend requests. Is clobbered.
     */
    private void processOutgoingFullyFormatted(byte[] plaintext, NodePeer pn) {
        BlockCipher sessionCipher = pn.getSessionCipher();
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize())
            throw new IllegalStateException("Block size must be half key size: blockSize="+
                    sessionCipher.getBlockSize()+", keySize="+sessionCipher.getKeySize());
        
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        
        int digestLength = md.getDigestLength();
        
        if(digestLength != blockSize)
            throw new IllegalStateException("Block size must be digest length!");
        
        byte[] output = new byte[plaintext.length + digestLength];
        System.arraycopy(plaintext, 0, output, digestLength, plaintext.length);
        
        md.update(plaintext);
        
        //Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));
        
        byte[] digestTemp;
        
        digestTemp = md.digest();

        //Logger.minor(this, "\nHash:      "+HexUtil.bytesToHex(digestTemp));
                
        // Put plaintext in output
        System.arraycopy(digestTemp, 0, output, 0, digestLength);
        
        sessionCipher.encipher(digestTemp, digestTemp);
        
        // Now copy it back
        System.arraycopy(digestTemp, 0, output, 0, digestLength);
        // Yay, we have an encrypted hash

        //Logger.minor(this, "\nEncrypted: "+HexUtil.bytesToHex(digestTemp)+" ("+plaintext.length+" bytes plaintext)");
        
        PCFBMode pcfb = new PCFBMode(sessionCipher, digestTemp);
        pcfb.blockEncipher(output, digestLength, plaintext.length);
        
        //Logger.minor(this, "Ciphertext:\n"+HexUtil.bytesToHex(output, digestLength, plaintext.length));
        
        // We have a packet
        // Send it
        
        Logger.minor(this,"Sending packet to "+pn);
        
        usm.sendPacket(output, pn.getPeer());
    }
}
