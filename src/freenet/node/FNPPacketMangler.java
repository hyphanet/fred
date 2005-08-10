package freenet.node;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import net.i2p.util.NativeBigInteger;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.EntropySource;
import freenet.crypt.PCFBMode;
import freenet.io.comm.*;
import freenet.io.comm.LowLevelFilter;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.UdpSocketManager;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.WouldBlockException;

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
     * Packets generated should have a PeerNode on them.
     * Note that the buffer can be modified by this method.
     */
    public void process(byte[] buf, int offset, int length, Peer peer) {
        node.random.acceptTimerEntropy(fnpTimingSource, 0.25);
        Logger.minor(this, "Packet length "+length+" from "+peer);

        /**
         * Look up the Peer.
         * If we know it, check the packet with that key.
         * Otherwise try all of them (on the theory that nodes 
         * occasionally change their IP addresses).
         */
        PeerNode opn = pm.getByPeer(peer);
        PeerNode pn;
        
        if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
            
            if(opn != null) {
                if(tryProcess(buf, offset, length, opn.getCurrentKeyTracker())) return;
                // Try with old key
                if(tryProcess(buf, offset, length, opn.getPreviousKeyTracker())) return;
                // Try for auth packets
                if(tryProcessAuth(buf, offset, length, opn, peer)) return;
            }
            for(int i=0;i<pm.connectedPeers.length;i++) {
                pn = pm.myPeers[i];
                if(pn == opn) continue;
                if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker())) {
                    // IP address change
                    pn.changedIP(peer);
                    return;
                }
                if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker())) return;
                if(tryProcessAuth(buf, offset, length, opn, peer)) return;
            }
        }
        Logger.error(this,"Unmatchable packet from "+peer);
    }

    /**
     * Is this a negotiation packet? If so, process it.
     * @param buf The buffer to read bytes from
     * @param offset The offset at which to start reading
     * @param length The number of bytes to read
     * @param opn The PeerNode we think is responsible
     * @param peer The Peer to send a reply to
     * @return True if we handled a negotiation packet, false otherwise.
     */
    private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerNode opn, Peer peer) {
        BlockCipher authKey = node.getAuthCipher();
        // Does the packet match IV E( H(data) data ) ?
        PCFBMode pcfb = new PCFBMode(authKey);
        int ivLength = pcfb.lengthIV();
        MessageDigest md = getDigest();
        int digestLength = md.getDigestLength();
        if(length < digestLength + ivLength + 4) {
            Logger.minor(this, "Too short");
            return false;
        }
        // IV at the beginning
        pcfb.reset(buf, offset);
        // Then the hash, then the data
        // => Data starts at ivLength + digestLength
        // Decrypt the hash
        byte[] hash = new byte[digestLength];
        System.arraycopy(buf, offset+ivLength, hash, 0, digestLength);
        pcfb.blockDecipher(hash, 0, hash.length);
        
        int dataStart = ivLength + digestLength + offset+1;
        
        int dataLength = pcfb.decipher(buf[dataStart-1]) & 0xff;
        
        // Decrypt the data
        byte[] payload = new byte[dataLength];
        System.arraycopy(buf, dataStart, payload, 0, dataLength);
        pcfb.blockDecipher(payload, 0, payload.length);
        
        md.update(payload);
        byte[] realHash = md.digest();
        
        if(Arrays.equals(realHash, hash)) {
            // Got one
            processDecryptedAuth(payload, opn, peer);
            return true;
        } else {
            Logger.minor(this, "Incorrect hash (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
            return false;
        }
    }

    /**
     * Process a decrypted, authenticated auth packet.
     * @param payload The packet payload, after it has been decrypted.
     */
    private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo) {
        /* Format:
         * 1 byte - version number (0)
         * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi)
         * 1 byte - packet type (0-3)
         */
        int version = payload[0];
        if(version != 0) {
            Logger.error(this, "Decrypted auth packet but invalid version: "+version);
            return;
        }
        int negType = payload[1];
        if(negType != 0) {
            Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
            return;
        }
        int packetType = payload[2];
        if(packetType < 0 || packetType > 3) {
            Logger.error(this, "Decrypted auth packet but unknown packet type "+packetType+" from "+replyTo+" possibly from "+pn);
            return;
        }
        // We keep one DiffieHellmanContext per node ONLY
        /*
         * Now, to the real meat
         * Alice, Bob share a base, g, and a modulus, p
         * Alice generates a random number r, and: 1: Alice -> Bob: a=g^r
         * Bob receives this and generates his own random number, s, and: 2: Bob -> Alice: b=g^s
         * Alice receives this, calculates K = b^r, and: 3: Alice -> Bob: E_K ( H(data) data )
         *    where data = [ Alice's startup number ]
         * Bob does exactly the same as Alice for packet 4.
         * 
         * At this point we are done.
         */
        if(packetType == 0) {
            // We are Bob
            // We need to:
            // - Record Alice's a
            // - Generate our own s and b
            // - Send a type 1 packet back to Alice containing this
            
            DiffieHellmanContext ctx = 
                processDHZeroOrOne(0, payload, pn);
            if(ctx == null) return;
            // Send reply
            sendFirstHalfDHPacket(1, ctx.getOurExponential(), pn, replyTo);
            // Send a type 1, they will reply with a type 2
        } else if(packetType == 1) {
            // We are Alice
            DiffieHellmanContext ctx = 
                processDHZeroOrOne(1, payload, pn);
            if(ctx == null) return;
            sendDHCompletion(2, ctx.getCipher(), pn, replyTo);
            // Send a type 2
        } else if(packetType == 2) {
            // We are Bob
            // Receiving a completion packet
            // Verify the packet, then complete
            // Format: IV E_K ( H(data) data )
            // Where data = [ long: bob's startup number ]
            DiffieHellmanContext ctx = 
                processDHTwoOrThree(2, payload, pn, replyTo);
            if(ctx != null)
                sendDHCompletion(3, ctx.getCipher(), pn, replyTo);
        } else if(packetType == 3) {
            // We are Alice
            processDHTwoOrThree(3, payload, pn, replyTo);
        }
    }

    /**
     * Send a DH completion message.
     * @param phase The packet phase number. Either 2 or 3.
     * @param cipher The negotiated cipher.
     * @param pn The PeerNode which we are talking to.
     * @param replyTo The Peer to which to send the packet (not necessarily the same
     * as the one on pn as the IP may have changed).
     */
    private void sendDHCompletion(int phase, BlockCipher cipher, PeerNode pn, Peer replyTo) {
        /** Format:
         * IV
         * Hash
         * Data
         * 
         * Where Data = our bootID
         * Very similar to the surrounding wrapper in fact.
         */
        PCFBMode pcfb = new PCFBMode(cipher);
        byte[] iv = new byte[pcfb.lengthIV()];
        
        byte[] data = Fields.longToBytes(node.bootID);
        
        MessageDigest md = getDigest();
        
        byte[] hash = md.digest(data);
        
        pcfb.blockEncipher(hash, 0, hash.length);
        pcfb.blockEncipher(data, 0, data.length);
        
        byte[] output = new byte[iv.length+hash.length+data.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(hash, 0, output, iv.length, hash.length);
        System.arraycopy(data, 0, output, iv.length + hash.length, data.length);
        
        sendAuthPacket(0, 0, phase, output, pn, replyTo);
    }

    /**
     * Send a first-half (phase 0 or 1) DH negotiation packet to the node.
     * @param phase The phase of the message to be sent (0 or 1).
     * @param integer
     * @param replyTo
     */
    private void sendFirstHalfDHPacket(int phase, NativeBigInteger integer, PeerNode pn, Peer replyTo) {
        Logger.minor(this, "Sending ("+phase+") "+integer.toHexString());
        byte[] data = integer.toByteArray();
        int targetLength = DiffieHellman.modulusLengthInBytes();
        if(data.length != targetLength) {
            byte[] newData = new byte[targetLength];
            if(data.length == targetLength+1 && data[0] == 0) {
                // Sign bit
                System.arraycopy(data, 1, newData, 0, targetLength);
            } else if(data.length < targetLength) {
                System.arraycopy(data, 0, newData, targetLength-data.length, data.length);
            } else {
                throw new IllegalStateException("Too long!");
            }
            data = newData;
        }
        Logger.minor(this, "Processed: "+HexUtil.bytesToHex(data));
        sendAuthPacket(0, 0, phase, data, pn, replyTo);
    }

    /**
     * Send an auth packet.
     */
    private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerNode pn, Peer replyTo) {
        byte[] output = new byte[data.length+3];
        output[0] = (byte) version;
        output[1] = (byte) negType;
        output[2] = (byte) phase;
        System.arraycopy(data, 0, output, 3, data.length);
        Logger.minor(this, "Sending auth packet to "+replyTo+" - version="+version+" negType="+negType+" phase="+phase+" data.length="+data.length+" for "+pn);
        sendAuthPacket(output, pn, replyTo);
    }

    /**
     * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
     */
    private void sendAuthPacket(byte[] output, PeerNode pn, Peer replyTo) {
        int length = output.length;
        if(length > 255) {
            throw new IllegalStateException("Cannot send auth packet: too long: "+length);
        }
        BlockCipher cipher = pn.getAuthKey();
        PCFBMode pcfb = new PCFBMode(cipher);
        byte[] iv = new byte[pcfb.lengthIV()];
        node.random.nextBytes(iv);
        MessageDigest md = getDigest();
        byte[] hash = md.digest(output);
        Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
        byte[] data = new byte[iv.length + hash.length + 1 /* length byte */ + output.length];
        pcfb.reset(iv);
        System.arraycopy(iv, 0, data, 0, iv.length);
        pcfb.blockEncipher(hash, 0, hash.length);
        System.arraycopy(hash, 0, data, iv.length, hash.length);
        data[hash.length+iv.length] = (byte) pcfb.encipher((byte)length);
        pcfb.blockEncipher(output, 0, output.length);
        System.arraycopy(output, 0, data, hash.length+iv.length+1, output.length);
        usm.sendPacket(data, replyTo);
        Logger.minor(this, "Sending auth packet to "+replyTo+" - size "+data.length+" data length: "+output.length);
    }

    /**
     * @param i
     * @param payload
     * @param pn
     * @param replyTo
     * @return
     */
    private DiffieHellmanContext processDHTwoOrThree(int i, byte[] payload, PeerNode pn, Peer replyTo) {
        DiffieHellmanContext ctx = pn.getDHContext();
        if(!ctx.canGetCipher()) {
            Logger.error(this, "Cannot get cipher");
            return null;
        }
        BlockCipher encKey = ctx.getCipher();
        PCFBMode pcfb = new PCFBMode(encKey);
        int ivLength = pcfb.lengthIV();
        if(payload.length-3 < HASH_LENGTH + ivLength + 8) {
            Logger.error(this, "Too short phase "+i+" packet from "+replyTo+" probably from "+pn);
            return null;
        }
        pcfb.reset(payload, 3); // IV
        byte[] hash = new byte[HASH_LENGTH];
        System.arraycopy(payload, 3+ivLength, hash, 0, HASH_LENGTH);
        pcfb.blockDecipher(hash, 0, HASH_LENGTH);
        int dataLength = payload.length - (ivLength + HASH_LENGTH + 3);
        if(dataLength < 0) {
            Logger.error(this, "Decrypted data length: "+dataLength+" but payload.length "+payload.length+" (others: "+(ivLength + HASH_LENGTH+3)+")");
            return null;
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(payload, 3+ivLength+HASH_LENGTH, data, 0, dataLength);
        pcfb.blockDecipher(data, 0, dataLength);
        // Check the hash
        MessageDigest md = getDigest();
        byte[] realHash = md.digest(data);
        if(Arrays.equals(realHash, hash)) {
            // Success!
            long bootID = Fields.bytesToLong(data);
            pn.completedHandshake(bootID, encKey, replyTo);
            return ctx;
        } else {
            Logger.error(this, "Failed to complete handshake (2) on "+pn+" for "+replyTo);
            return null;
        }
    }

    /**
     * Process a phase-0 or phase-1 Diffie-Hellman packet.
     * @return a DiffieHellmanContext if we succeeded, otherwise null.
     */
    private DiffieHellmanContext processDHZeroOrOne(int phase, byte[] payload, PeerNode pn) {
        
        if(phase == 0 && pn.hasLiveHandshake(System.currentTimeMillis())) {
            Logger.minor(this, "Rejecting phase "+phase+" handshake - already running one");
            return null;
        }
        
        // First, get the BigInteger
        int length = DiffieHellman.modulusLengthInBytes();
        if(payload.length < length + 3) {
            Logger.error(this, "Packet too short: "+payload.length+" after decryption in DH("+phase+"), should be "+(length + 3));
            return null;
        }
        byte[] aAsBytes = new byte[length];
        System.arraycopy(payload, 3, aAsBytes, 0, length);
        NativeBigInteger a = new NativeBigInteger(1, aAsBytes);
        DiffieHellmanContext ctx;
        if(phase == 1) {
            ctx = pn.getDHContext();
        } else {
            ctx = DiffieHellman.generateContext();
            // Don't calculate the key until we need it
            pn.setDHContext(ctx);
        }
        ctx.setOtherSideExponential(a);
        Logger.minor(this, "His exponential: "+a.toHexString());
        // REDFLAG: This is of course easily DoS'ed if you know the node.
        // We will fix this by means of JFKi.
        return ctx;
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
     * Try to process an incoming packet with a given PeerNode.
     * We need to know where the packet has come from in order to
     * decrypt and authenticate it.
     */
    private boolean tryProcess(byte[] buf, int offset, int length, KeyTracker tracker) {
        // Need to be able to call with tracker == null to simplify code above 
        if(tracker == null) return false;
        Logger.minor(this,"Entering tryProcess: "+buf+","+offset+","+length+","+tracker);
        /**
         * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
         * 
         * So first two blocks are the hash, PCBC encoded (meaning the
         * first one is ECB, and the second one is ECB XORed with the 
         * ciphertext and plaintext of the first block).
         */
        BlockCipher sessionCipher = tracker.sessionCipher;
        if(sessionCipher == null) {
            Logger.minor(this, "No cipher");
            return false;
        }
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

        int targetSeqNumber = tracker.highestReceivedIncomingSeqNumber();
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
            Logger.minor(this, "Packet possibly from "+tracker+" hash does not match:\npacketHash="+
                    HexUtil.bytesToHex(packetHash)+"\n  realHash="+HexUtil.bytesToHex(realHash)+" ("+(length-HASH_LENGTH)+" bytes payload)");
            return false;
        }
        
        if(seqNumber != -1 && tracker.alreadyReceived(seqNumber)) {
            tracker.queueAck(seqNumber);
            Logger.normal(this, "Received packet twice: "+seqNumber);
            return true;
        }
        
        for(int i=0;i<md.getDigestLength();i++) {
            packetHash[i] ^= buf[offset+i];
        }
        node.random.acceptEntropyBytes(myPacketDataSource, packetHash, 0, md.getDigestLength(), 0.5);
        
        // Lots more to do yet!
        processDecryptedData(plaintext, seqNumber, tracker);
        return true;
    }

    /**
     * Process an incoming packet, once it has been decrypted.
     * @param decrypted The packet's contents.
     * @param seqNumber The detected sequence number of the packet.
     * @param tracker The KeyTracker responsible for the key used to encrypt the packet.
     */
    private void processDecryptedData(byte[] decrypted, int seqNumber, KeyTracker tracker) {
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
            Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            return;
        }
        if(version != 0) {
            Logger.error(this,"Packet from "+tracker+" decrypted but invalid version: "+version);
            return;
        }

        /** Highest sequence number sent - not the same as this packet's seq number */
        int realSeqNumber = seqNumber;
        
        if(seqNumber == -1) {
            if(ptr+4 > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
                return;
            }
            realSeqNumber =
                ((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
                        (decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
            ptr+=4;
        } else {
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
                return;
            }
            realSeqNumber = seqNumber + (decrypted[ptr++] & 0xff);
        }
        
        //Logger.minor(this, "Reference seq number: "+HexUtil.bytesToHex(decrypted, ptr, 4));
        
        if(ptr+4 > decrypted.length) {
            Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            return;
        }
        int referenceSeqNumber = 
            ((((((decrypted[ptr+0] & 0xff) << 8) + (decrypted[ptr+1] & 0xff)) << 8) + 
                    (decrypted[ptr+2] & 0xff)) << 8) + (decrypted[ptr+3] & 0xff);
        ptr+=4;
        
        Logger.minor(this, "Reference sequence number: "+referenceSeqNumber);

        tracker.receivedPacket(seqNumber);
        
        int ackCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Acks: "+ackCount);
        
        for(int i=0;i<ackCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
                return;
            }
            int realSeqNo = referenceSeqNumber - offset;
            Logger.minor(this, "ACK: "+realSeqNo);
            tracker.acknowledgedPacket(realSeqNo);
        }
        
        int retransmitCount = decrypted[ptr++] & 0xff;
        Logger.minor(this, "Retransmit requests: "+retransmitCount);
        
        for(int i=0;i<retransmitCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = referenceSeqNumber - offset;
            Logger.minor(this, "RetransmitRequest: "+realSeqNo);
            tracker.resendPacket(realSeqNo);
        }

        int ackRequestsCount = decrypted[ptr++] & 0xff;
        
        // These two are relative to our outgoing packet number
        // Because they relate to packets we have sent.
        for(int i=0;i<ackRequestsCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = realSeqNumber - offset;
            Logger.minor(this, "AckRequest: "+realSeqNo);
            tracker.receivedAckRequest(realSeqNo);
        }
        
        int forgottenCount = decrypted[ptr++] & 0xff;
        //Logger.minor(this, "Forgotten packets: "+forgottenCount);
        
        for(int i=0;i<forgottenCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = realSeqNumber - offset;
            tracker.destForgotPacket(realSeqNo);
        }

        if(seqNumber == -1) return;
        // No sequence number == no messages
        
        int messages = decrypted[ptr++] & 0xff;
        
        for(int i=0;i<messages;i++) {
            if(ptr+1 > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int length = ((decrypted[ptr++] & 0xff) << 8) +
            	(decrypted[ptr++] & 0xff);
            if(length > decrypted.length - ptr) {
                Logger.error(this, "Message longer than remaining space: "+length);
                return;
            }
            Message m = usm.decodePacket(decrypted, ptr, length, tracker.pn);
            ptr+=length;
            if(m != null) {
                //Logger.minor(this, "Dispatching packet: "+m);
                usm.checkFilters(m);
            }
        }
    }

    /**
     * Build a packet and send it, from a whole bunch of messages.
     */
    public void processOutgoing(Message[] messages, PeerNode pn, boolean neverWaitForPacketNumber) throws NotConnectedException, WouldBlockException {
        byte[][] messageData = new byte[messages.length][];
        int length = 1;
        for(int i=0;i<messageData.length;i++) {
            messageData[i] = messages[i].encodeToPacket(this, pn);
            length += (messageData[i].length + 2);
        }
        if(length < node.usm.getMaxPacketSize() &&
                messages.length < 256) {
            innerProcessOutgoing(messageData, 0, messageData.length, length, pn, neverWaitForPacketNumber);
        } else {
            length = 1;
            int count = 0;
            int lastIndex = 0;
            for(int i=0;i<messages.length;i++) {
                int thisLength = (messageData[i].length + 2);
                int newLength = length + thisLength;
                if(thisLength > node.usm.getMaxPacketSize()) {
                    Logger.error(this, "Message exceeds packet size: "+messages[i]);
                    // Send the last lot, then send this
                }
                count++;
                if(newLength > node.usm.getMaxPacketSize() || count > 255) {
                    innerProcessOutgoing(messageData, lastIndex, i-lastIndex, length, pn, neverWaitForPacketNumber);
                    lastIndex = i;
                    length = (messageData[i].length + 2);
                    count = 1;
                } else length = newLength;
            }
        }
    }
    
    /**
     * Send some messages.
     * @param messageData An array block of messages.
     * @param start Index to start reading the array.
     * @param length Number of messages to read.
     * @param bufferLength Size of the buffer to write into.
     * @param pn Node to send the messages to.
     */
    private void innerProcessOutgoing(byte[][] messageData, int start, int length, int bufferLength, PeerNode pn, boolean neverWaitForPacketNumber) throws NotConnectedException, WouldBlockException {
        Logger.minor(this, "innerProcessOutgoing(...,"+start+","+length+","+bufferLength);
        byte[] buf = new byte[bufferLength];
        buf[0] = (byte)length;
        int loc = 1;
        for(int i=start;i<(start+length);i++) {
            byte[] data = messageData[i];
            int len = data.length;
            buf[loc++] = (byte)(len >> 8);
            buf[loc++] = (byte)len;
            System.arraycopy(data, 0, buf, loc, len);
            loc += len;
        }
        processOutgoingPreformatted(buf, 0, bufferLength, pn, neverWaitForPacketNumber);
    }

    /**
     * Build a packet and send it. From a Message recently converted into byte[],
     * but with no outer formatting.
     */
    public void processOutgoing(byte[] buf, int offset, int length, PeerContext peer) throws NotConnectedException {
        if(!(peer instanceof PeerNode))
            throw new IllegalArgumentException();
        PeerNode pn = (PeerNode)peer;
        byte[] newBuf = preformat(buf, offset, length);
        processOutgoingPreformatted(newBuf, 0, newBuf.length, pn, -1);
    }


    /**
     * Build a packet and send it. From a Message recently converted into byte[],
     * but with no outer formatting.
     */
    public void processOutgoing(byte[] buf, int offset, int length, KeyTracker tracker) throws KeyChangedException, NotConnectedException {
        byte[] newBuf = preformat(buf, offset, length);
        processOutgoingPreformatted(newBuf, 0, newBuf.length, tracker, -1);
    }
    
    /**
     * Send a packet using the current key. Retry if it fails solely because
     * the key changes.
     */
    void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerNode peer, int k) throws NotConnectedException {
        while(true) {
            try {
                KeyTracker tracker = peer.getCurrentKeyTracker();
                if(tracker == null) {
                    Logger.normal(this, "Dropping packet: Not connected yet");
                    throw new NotConnectedException();
                }
                processOutgoingPreformatted(buf, offset, length, tracker, k);
                return;
            } catch (KeyChangedException e) {
                // Go around again
            }
        }
    }

    /**
     * Send a packet using the current key. Retry if it fails solely because
     * the key changes.
     */
    void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerNode peer, boolean neverWaitForPacketNumber) throws NotConnectedException, WouldBlockException {
        while(true) {
            try {
                KeyTracker tracker = peer.getCurrentKeyTracker();
                if(tracker == null) {
                    Logger.normal(this, "Dropping packet: Not connected yet");
                    throw new NotConnectedException();
                }
                int seqNo = neverWaitForPacketNumber ? tracker.allocateOutgoingPacketNumberNeverBlock() :
                    tracker.allocateOutgoingPacketNumber();
                processOutgoingPreformatted(buf, offset, length, tracker, seqNo);
                return;
            } catch (KeyChangedException e) {
                // Go around again
            }
        }
    }

    public byte[] preformat(Message msg, PeerNode pn) {
        byte[] buf = msg.encodeToPacket(this, pn);
        return preformat(buf, 0, buf.length);
    }
    
    public byte[] preformat(byte[] buf, int offset, int length) {
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
    
    /**
     * Encrypt a packet, prepend packet acks and packet resend requests, and send it. 
     * The provided data is ready-formatted, meaning that it already has the message 
     * length's and message counts.
     * @param buf Buffer to read data from.
     * @param offset Point at which to start reading.
     * @param length Number of bytes to read.
     * @param tracker The KeyTracker to use to encrypt the packet and send it to the
     * associated PeerNode.
     * @param packetNumber If specified, force use of this particular packet number.
     * Means this is a resend of a dropped packet.
     * @throws NotConnectedException If the node is not connected.
     * @throws KeyChangedException If the primary key changes while we are trying to send this packet.
     */
    public void processOutgoingPreformatted(byte[] buf, int offset, int length, KeyTracker tracker, int packetNumber) throws KeyChangedException, NotConnectedException {
        if(tracker == null || (!tracker.pn.isConnected())) {
            throw new NotConnectedException();
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
                seqNumber = tracker.allocateOutgoingPacketNumber();
        }
        
        Logger.minor(this, "Sequence number (sending): "+seqNumber+" ("+packetNumber+") to "+tracker.pn.getPeer());
        
        int[] acks = tracker.grabAcks();
        int[] resendRequests = tracker.grabResendRequests();
        int[] ackRequests = tracker.grabAckRequests();
        
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
            realSeqNumber = tracker.getLastOutgoingSeqNumber();
            plaintext[ptr++] = (byte)(realSeqNumber >> 24);
            plaintext[ptr++] = (byte)(realSeqNumber >> 16);
            plaintext[ptr++] = (byte)(realSeqNumber >> 8);
            plaintext[ptr++] = (byte)realSeqNumber;
        } else {
            realSeqNumber = tracker.getLastOutgoingSeqNumber();
            plaintext[ptr++] = (byte)(realSeqNumber - seqNumber);
        }
        
        int otherSideSeqNumber = tracker.highestReceivedIncomingSeqNumber();
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
            tracker.sentPacket(saveable, seqNumber);
        }
        
        Logger.minor(this, "Sending...");

        processOutgoingFullyFormatted(plaintext, tracker);
        Logger.minor(this, "Sent packet");
    }

    /**
     * Encrypt and send a packet.
     * @param plaintext The packet's plaintext, including all formatting,
     * including acks and resend requests. Is clobbered.
     */
    private void processOutgoingFullyFormatted(byte[] plaintext, KeyTracker kt) {
        BlockCipher sessionCipher = kt.sessionCipher;
        if(sessionCipher == null) {
            Logger.error(this, "Dropping packet send - have not handshaked yet");
            return;
        }
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
        
        Logger.minor(this,"Sending packet of length "+output.length+" to "+kt.pn);
        
        usm.sendPacket(output, kt.pn.getPeer());
        kt.pn.sentPacket();
    }

    /**
     * Send a handshake, if possible, to the node.
     * @param pn
     */
    public void sendHandshake(PeerNode pn) {
        DiffieHellmanContext ctx;
        synchronized(pn) {
            if(!pn.shouldSendHandshake()) {
                return;
            } else {
                ctx = DiffieHellman.generateContext();
                pn.setDHContext(ctx);
            }
        }
        sendFirstHalfDHPacket(0, ctx.getOurExponential(), pn, pn.getPeer());
    }
}
