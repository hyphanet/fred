/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.security.MessageDigest;
import java.util.Arrays;

import net.i2p.util.NativeBigInteger;

import freenet.crypt.BlockCipher;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanContext;
import freenet.crypt.EntropySource;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.StationToStationContext;
import freenet.io.comm.*;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.TimeUtil;
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

	private static boolean logMINOR;
    final Node node;
    final PeerManager pm;
    final UdpSocketManager usm;
    static final int MAX_PACKETS_IN_FLIGHT = 256; 
    static final EntropySource fnpTimingSource = new EntropySource();
    static final EntropySource myPacketDataSource = new EntropySource();
    static final int RANDOM_BYTES_LENGTH = 12;
    static final int HASH_LENGTH = 32;
    /** Minimum headers overhead */
	public static final int HEADERS_LENGTH_MINIMUM =
		4 + // sequence number
    	RANDOM_BYTES_LENGTH + // random junk
    	1 + // version
    	1 + // assume seqno != -1; otherwise would be 4
    	4 + // other side's seqno
    	1 + // number of acks
    	0 + // assume no acks
    	1 + // number of resend reqs
    	0 + // assume no resend requests
    	1 + // number of ack requests
    	0 + // assume no ack requests
    	1 + // no forgotten packets
    	HASH_LENGTH + // hash
    	1; // number of messages
	/** Headers overhead if there is one message and no acks. */
	public static final int HEADERS_LENGTH_ONE_MESSAGE = 
		HEADERS_LENGTH_MINIMUM + 2; // 2 bytes = length of message. rest is the same.
    
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
     * Packets generated should have a PeerNode on them.
     * Note that the buffer can be modified by this method.
     */
    public void process(byte[] buf, int offset, int length, Peer peer) {
        node.random.acceptTimerEntropy(fnpTimingSource, 0.25);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Packet length "+length+" from "+peer);

        /**
         * Look up the Peer.
         * If we know it, check the packet with that key.
         * Otherwise try all of them (on the theory that nodes 
         * occasionally change their IP addresses).
         */
        PeerNode opn = pm.getByPeer(peer);
        PeerNode pn;
        
        if(opn != null) {
            if(logMINOR) Logger.minor(this, "Trying exact match");
            if(length > HEADERS_LENGTH_MINIMUM) {
                if(tryProcess(buf, offset, length, opn.getCurrentKeyTracker())) return;
                // Try with old key
                if(tryProcess(buf, offset, length, opn.getPreviousKeyTracker())) return;
                // Try with unverified key
                if(tryProcess(buf, offset, length, opn.getUnverifiedKeyTracker())) return;
            }
            if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
                // Might be an auth packet
                if(tryProcessAuth(buf, offset, length, opn, peer)) return;
            }
        }
        if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
            for(int i=0;i<pm.connectedPeers.length;i++) {
                pn = pm.myPeers[i];
                if(pn == opn) continue;
                if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker())) {
                    // IP address change
                    pn.changedIP(peer);
                    return;
                }
                if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker())) {
                    // IP address change
                    pn.changedIP(peer);
                    return;
                }
                if(tryProcess(buf, offset, length, pn.getUnverifiedKeyTracker())) {
                    // IP address change
                    pn.changedIP(peer);
                    return;
                }
            }
        }
        if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
            for(int i=0;i<pm.myPeers.length;i++) {
                pn = pm.myPeers[i];
                if(pn == opn) continue;
                if(tryProcessAuth(buf, offset, length, pn, peer)) return;
            }
        }
        if((opn != null) && !opn.isConnected()) {
            if(logMINOR) Logger.minor(this,"Unmatchable packet from "+peer);
        } else
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
        BlockCipher authKey = opn.incomingSetupCipher;
        if(logMINOR) Logger.minor(this, "Decrypt key: "+HexUtil.bytesToHex(opn.incomingSetupKey)+" for "+peer+" : "+opn);
        // Does the packet match IV E( H(data) data ) ?
        PCFBMode pcfb = new PCFBMode(authKey);
        int ivLength = pcfb.lengthIV();
        MessageDigest md = SHA256.getMessageDigest();
        int digestLength = md.getDigestLength();
        if(length < digestLength + ivLength + 4) {
            if(logMINOR) Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 4));
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
        
        int dataStart = ivLength + digestLength + offset+2;
        
        int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
        int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
        int dataLength = (byte1 << 8) + byte2;
        if(logMINOR) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
        if(dataLength > length - (ivLength+hash.length+2)) {
            if(logMINOR) Logger.minor(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
            return false;
        }
        // Decrypt the data
        byte[] payload = new byte[dataLength];
        System.arraycopy(buf, dataStart, payload, 0, dataLength);
        pcfb.blockDecipher(payload, 0, payload.length);
        
        md.update(payload);
        byte[] realHash = md.digest();
        
        if(Arrays.equals(realHash, hash)) {
            // Got one
            processDecryptedAuth(payload, opn, peer);
            opn.reportIncomingBytes(length);
            return true;
        } else {
            if(logMINOR) Logger.minor(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
            return false;
        }
    }

    /**
     * Process a decrypted, authenticated auth packet.
     * @param payload The packet payload, after it has been decrypted.
     */
    private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo) {
        if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn);
        if(pn.isDisabled()) {
        	if(logMINOR) Logger.minor(this, "Won't connect to a disabled peer ("+pn+ ')');
    		return;  // We don't connect to disabled peers
    	}
        
        long now = System.currentTimeMillis();
    	int delta = (int) (now - pn.lastSentPacketTime());
    	
        int negType = payload[1];
        int packetType = payload[2];
        int version = payload[0];
    	
        if(logMINOR) Logger.minor(this, "Received auth packet for "+pn.getPeer()+" (phase="+packetType+", v="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) from "+replyTo+"");
        
        /* Format:
         * 1 byte - version number (1)
         * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi || 1 = StS)
         * 1 byte - packet type (0-3)
         */
        if(version != 1) {
            Logger.error(this, "Decrypted auth packet but invalid version: "+version);
            return;
        }

        if((negType < 0) || (negType > 1)) {
            Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
            return;
        }else if (negType == 0){
        	// We are gonna do unauthenticated DH FIXME: disable when StS is deployed.
        
        	if((packetType < 0) || (packetType > 3)) {
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
        		processDHTwoOrThree(2, payload, pn, replyTo, true);
        	} else if(packetType == 3) {
        		// We are Alice
        		processDHTwoOrThree(3, payload, pn, replyTo, false);
        	}
        }else if (negType == 1){
        	// We are gonna do simple StS
        
        	if((packetType < 0) || (packetType > 3)) {
        		Logger.error(this, "Decrypted auth packet but unknown packet type "+packetType+" from "+replyTo+" possibly from "+pn);
        		return;
        	}
        	//  We keep one StationToStationContext per node ONLY
        	/*
        	 * Now, to the real meat
        	 * 
        	 *  1a. Alice generates a random number x and computes and sends the exponential g^x to Bob.
        	 *  
        	 *	2a. Bob generates a random number y and computes the exponential g^y.    
        	 *	2b. Bob computes the shared secret key K = (g^x)^y.
        	 *	2c. Bob concatenates the exponentials (g^y, g^x) (order is important),
        	 * 	  signs them using his asymmetric key B, and then encrypts them with K.
        	 *  2d. He sends the ciphertext along with his own exponential g^y to Alice.
        	 *  
        	 *  3a. Alice computes the shared secret key K = (gy)x.
        	 *  3b. Alice decrypts and verifies Bob's signature.
        	 *  3c. Alice concatenates the exponentials (g^x, g^y) (order is important),
        	 *    signs them using her asymmetric key A, and then encrypts them with K.
        	 *  3d. She sends the ciphertext to Bob.
        	 *  
        	 *  4. Bob decrypts and verifies Alice's signature.
        	 * 
        	 * 	Alice and Bob are now mutually authenticated and have a shared secret.
        	 *  This secret, K, can then be used to encrypt further communication.
        	 *  
        	 *  I suggest we add one more phase to simplify the code : 2d is splited up
        	 *  into two packets so that both alice and bob send the same kind of packets. 
        	 */
        	
        	// be carefull with StationToStationContext constructors ; it will be expensive in terms of cpu and can be DoSed, on the other hand, when shall we reset the context ? maybe creating a new packetType ... with a time based restriction 
        	if(packetType == 0) { // 0 won't be received, but we need to initialize the exchange
        		StationToStationContext ctx = pn.getKeyAgreementSchemeContext()== null ? new StationToStationContext(node.getMyPrivKey(), pn.peerCryptoGroup, pn.peerPubKey, node.random) : (StationToStationContext)pn.getKeyAgreementSchemeContext();
        		if(ctx == null) return;
        		pn.setKeyAgreementSchemeContext(ctx);
        		// We send g^x
        		sendFirstStSPacket(1, ctx.getOurExponential(), pn, replyTo);
        	} else if(packetType == 2) {
        		StationToStationContext ctx = pn.getKeyAgreementSchemeContext()== null ? new StationToStationContext(node.getMyPrivKey(), pn.peerCryptoGroup, pn.peerPubKey, node.random) : (StationToStationContext)pn.getKeyAgreementSchemeContext();
        		if(ctx == null) return;
        		pn.setKeyAgreementSchemeContext(ctx);
        		// We got g^y
        		ctx.setOtherSideExponential(new NativeBigInteger(payload));
        		// We send E(S(H( our exponential, his exponential)))
        		sendSecondStSPacket(3, ctx, pn, replyTo, payload);
        	} else if(packetType == 1) {
        		StationToStationContext ctx = (StationToStationContext) pn.getKeyAgreementSchemeContext();
        		if(ctx == null) return;
        		// We got g^x
        		ctx.setOtherSideExponential(new NativeBigInteger(payload));
        		// We send g^y
        		sendFirstStSPacket(2, ctx.getOurExponential(), pn, replyTo);
        		// We send E(S(H( our exponential, his exponential)))
        		sendSecondStSPacket(3, ctx, pn, replyTo, payload);
        	} else if(packetType == 3) {
        		StationToStationContext ctx = (StationToStationContext) pn.getKeyAgreementSchemeContext();
        		if(ctx == null) return;
        		if(!ctx.isAuthentificationSuccessfull(payload)) return;
        		// we are done if the above test is sucessfull!
        	}
        	
        	/*
        	 * We need some kind of locking above... and maybe some DoS protection
        	 */
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
        
        byte[] myRef = node.myCompressedSetupRef();
        byte[] data = new byte[myRef.length + 8];
        System.arraycopy(Fields.longToBytes(node.bootID), 0, data, 0, 8);
        System.arraycopy(myRef, 0, data, 8, myRef.length);
        
        MessageDigest md = SHA256.getMessageDigest();
        
        byte[] hash = md.digest(data);
        
        pcfb.blockEncipher(hash, 0, hash.length);
        pcfb.blockEncipher(data, 0, data.length);
        
        byte[] output = new byte[iv.length+hash.length+data.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(hash, 0, output, iv.length, hash.length);
        System.arraycopy(data, 0, output, iv.length + hash.length, data.length);
        
        sendAuthPacket(1, 0, phase, output, pn, replyTo);
    }

    /**
     * Send a first-half (phase 0 or 1) DH negotiation packet to the node.
     * @param phase The phase of the message to be sent (0 or 1).
     * @param integer
     * @param replyTo
     */
    private void sendFirstHalfDHPacket(int phase, NativeBigInteger integer, PeerNode pn, Peer replyTo) {
        long time1 = System.currentTimeMillis();
        if(logMINOR) Logger.minor(this, "Sending ("+phase+") "+integer.toHexString()+" to "+pn.getPeer());
        byte[] data = integer.toByteArray();
        int targetLength = DiffieHellman.modulusLengthInBytes();
        if(data.length != targetLength) {
            byte[] newData = new byte[targetLength];
            if((data.length == targetLength+1) && (data[0] == 0)) {
                // Sign bit
                System.arraycopy(data, 1, newData, 0, targetLength);
            } else if(data.length < targetLength) {
                System.arraycopy(data, 0, newData, targetLength-data.length, data.length);
            } else {
                throw new IllegalStateException("Too long!");
            }
            data = newData;
        }
        if(logMINOR) Logger.minor(this, "Processed: "+HexUtil.bytesToHex(data));
        long time2 = System.currentTimeMillis();
        if((time2 - time1) > 200) {
          Logger.error(this, "sendFirstHalfDHPacket: time2 is more than 200ms after time1 ("+(time2 - time1)+") working on "+replyTo+" of "+pn.getName());
        }
        sendAuthPacket(1, 0, phase, data, pn, replyTo);
        long time3 = System.currentTimeMillis();
        if((time3 - time2) > 500) {
          Logger.error(this, "sendFirstHalfDHPacket:sendAuthPacket() time3 is more than half a second after time2 ("+(time3 - time2)+") working on "+replyTo+" of "+pn.getName());
        }
        if((time3 - time1) > 500) {
          Logger.error(this, "sendFirstHalfDHPacket: time3 is more than half a second after time1 ("+(time3 - time1)+") working on "+replyTo+" of "+pn.getName());
        }
    }

    /**
     * Send an auth packet.
     */
    private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerNode pn, Peer replyTo) {
        long now = System.currentTimeMillis();
        long delta = now - pn.lastSentPacketTime();
        byte[] output = new byte[data.length+3];
        output[0] = (byte) version;
        output[1] = (byte) negType;
        output[2] = (byte) phase;
        System.arraycopy(data, 0, output, 3, data.length);
        if(logMINOR) Logger.minor(this, "Sending auth packet for "+pn.getPeer()+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+TimeUtil.formatTime(delta, 2, true)+" ago) to "+replyTo+" data.length="+data.length);
        sendAuthPacket(output, pn, replyTo);
    }

    /**
     * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
     */
    private void sendAuthPacket(byte[] output, PeerNode pn, Peer replyTo) {
        int length = output.length;
        if(length > node.usm.getMaxPacketSize()) {
            throw new IllegalStateException("Cannot send auth packet: too long: "+length);
        }
        BlockCipher cipher = pn.outgoingSetupCipher;
        if(logMINOR) Logger.minor(this, "Outgoing cipher: "+HexUtil.bytesToHex(pn.outgoingSetupKey));
        PCFBMode pcfb = new PCFBMode(cipher);
        byte[] iv = new byte[pcfb.lengthIV()];
        node.random.nextBytes(iv);
        MessageDigest md = SHA256.getMessageDigest();
        byte[] hash = md.digest(output);
        if(logMINOR) Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
        byte[] data = new byte[iv.length + hash.length + 2 /* length */ + output.length];
        pcfb.reset(iv);
        System.arraycopy(iv, 0, data, 0, iv.length);
        pcfb.blockEncipher(hash, 0, hash.length);
        System.arraycopy(hash, 0, data, iv.length, hash.length);
        if(logMINOR) Logger.minor(this, "Payload length: "+length);
        data[hash.length+iv.length] = (byte) pcfb.encipher((byte)(length>>8));
        data[hash.length+iv.length+1] = (byte) pcfb.encipher((byte)length);
        pcfb.blockEncipher(output, 0, output.length);
        System.arraycopy(output, 0, data, hash.length+iv.length+2, output.length);
        try {
        	sendPacket(data, replyTo, pn, 0);
		} catch (LocalAddressException e) {
			Logger.error(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn);
		}
		if(logMINOR) Logger.minor(this, "Sending auth packet (long) to "+replyTo+" - size "+data.length+" data length: "+output.length);
     }

    private void sendPacket(byte[] data, Peer replyTo, PeerNode pn, int alreadyReportedBytes) throws LocalAddressException {
    	if(pn.isIgnoreSourcePort()) {
    		Peer p = pn.getPeer();
    		if(p != null) replyTo = p;
    	}
    	usm.sendPacket(data, replyTo, pn.allowLocalAddresses());
    	pn.reportOutgoingBytes(data.length);
    	node.outputThrottle.forceGrab(data.length - alreadyReportedBytes);
	}

	/**
     * @param i
     * @param payload
     * @param pn
     * @param replyTo
     * @return
     */
    private DiffieHellmanContext processDHTwoOrThree(int i, byte[] payload, PeerNode pn, Peer replyTo, boolean sendCompletion) {
        DiffieHellmanContext ctx = (DiffieHellmanContext) pn.getKeyAgreementSchemeContext();
        if((ctx == null) || !ctx.canGetCipher()) {
            if(shouldLogErrorInHandshake()) {
                Logger.error(this, "Cannot get cipher");
            }
            return null;
        }
        byte[] encKey = ctx.getKey();
        BlockCipher cipher = ctx.getCipher();
        PCFBMode pcfb = new PCFBMode(cipher);
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
            Logger.error(this, "Decrypted data length: "+dataLength+" but payload.length "+payload.length+" (others: "+(ivLength + HASH_LENGTH+3)+ ')');
            return null;
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(payload, 3+ivLength+HASH_LENGTH, data, 0, dataLength);
        pcfb.blockDecipher(data, 0, dataLength);
        // Check the hash
        MessageDigest md = SHA256.getMessageDigest();
        byte[] realHash = md.digest(data);
        if(Arrays.equals(realHash, hash)) {
            // Success!
            long bootID = Fields.bytesToLong(data);
            // Send the completion before parsing the data, because this is easiest
            // Doesn't really matter - if it fails, we get loads of errors anyway...
            // Only downside is that the other side might still think we are connected for a while.
            // But this should be extremely rare.
            // REDFLAG?
            // We need to send the completion before the PN sends any packets, that's all...
            if(pn.completedHandshake(bootID, data, 8, data.length-8, cipher, encKey, replyTo, i == 2)) {
                if(sendCompletion)
                    sendDHCompletion(3, ctx.getCipher(), pn, replyTo);
                pn.maybeSendInitialMessages();
            }
            return ctx;
        } else {
            Logger.error(this, "Failed to complete handshake (2) on "+pn+" for "+replyTo);
            return null;
        }
    }

    /**
     * Should we log an error for an event that could easily be
     * caused by a handshake across a restart boundary?
     */
    private boolean shouldLogErrorInHandshake() {
        long now = System.currentTimeMillis();
        if(now - node.startupTime < Node.HANDSHAKE_TIMEOUT*2)
            return false;
        return true;
    }

    /**
     * Process a phase-0 or phase-1 Diffie-Hellman packet.
     * @return a DiffieHellmanContext if we succeeded, otherwise null.
     */
    private DiffieHellmanContext processDHZeroOrOne(int phase, byte[] payload, PeerNode pn) {
        
        if((phase == 0) && pn.hasLiveHandshake(System.currentTimeMillis())) {
            if(logMINOR) Logger.minor(this, "Rejecting phase "+phase+" handshake on "+pn+" - already running one");
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
            ctx = (DiffieHellmanContext) pn.getKeyAgreementSchemeContext();
            if(ctx == null) {
                if(shouldLogErrorInHandshake())
                    Logger.error(this, "Could not get context for phase 1 handshake from "+pn);
                return null;
            }
        } else {
            ctx = DiffieHellman.generateContext();
            // Don't calculate the key until we need it
            pn.setKeyAgreementSchemeContext(ctx);
        }
        ctx.setOtherSideExponential(a);
        if(logMINOR) Logger.minor(this, "His exponential: "+a.toHexString());
        // REDFLAG: This is of course easily DoS'ed if you know the node.
        // We will fix this by means of JFKi.
        return ctx;
    }

    /**
     * Try to process an incoming packet with a given PeerNode.
     * We need to know where the packet has come from in order to
     * decrypt and authenticate it.
     */
    private boolean tryProcess(byte[] buf, int offset, int length, KeyTracker tracker) {
        // Need to be able to call with tracker == null to simplify code above
        if(tracker == null) {
            if(logMINOR) Logger.minor(this, "Tracker == null");
            return false;
        }
        if(logMINOR) Logger.minor(this,"Entering tryProcess: "+Fields.hashCode(buf)+ ',' +offset+ ',' +length+ ',' +tracker);
        /**
         * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
         * 
         * So first two blocks are the hash, PCBC encoded (meaning the
         * first one is ECB, and the second one is ECB XORed with the 
         * ciphertext and plaintext of the first block).
         */
        BlockCipher sessionCipher = tracker.sessionCipher;
        if(sessionCipher == null) {
        	if(logMINOR) Logger.minor(this, "No cipher");
            return false;
        }
        if(logMINOR) Logger.minor(this, "Decrypting with "+HexUtil.bytesToHex(tracker.sessionKey));
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
        if(logMINOR) Logger.minor(this, "Seqno: "+seqNumber+" (highest seen "+targetSeqNumber+") receiving packet from "+tracker.pn.getPeer());

        if(seqNumber == -1) {
            // Ack/resendreq-only packet
        } else {
            // Now is it credible?
            // As long as it's within +/- 256, this is valid.
            if((targetSeqNumber != -1) && (Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT))
                return false;
        }
        if(logMINOR) Logger.minor(this, "Sequence number received: "+seqNumber);
        
        // Plausible, so lets decrypt the rest of the data
        
        byte[] plaintext = new byte[length-(4+HASH_LENGTH)];
        System.arraycopy(buf, offset+HASH_LENGTH+4, plaintext, 0, length-(HASH_LENGTH+4));
        
        pcfb.blockDecipher(plaintext, 0, length-(HASH_LENGTH+4));
        
        //Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));
        
        MessageDigest md = SHA256.getMessageDigest();
        md.update(seqBuf);
        md.update(plaintext);
        byte[] realHash = md.digest();

        // Now decrypt the original hash
        
        byte[] temp = new byte[blockSize];
        System.arraycopy(buf, offset, temp, 0, blockSize);
        sessionCipher.decipher(temp, temp);
        System.arraycopy(temp, 0, packetHash, 0, blockSize);
        
        // Check the hash
        if(!Arrays.equals(packetHash, realHash)) {
            if(logMINOR) Logger.minor(this, "Packet possibly from "+tracker+" hash does not match:\npacketHash="+
                    HexUtil.bytesToHex(packetHash)+"\n  realHash="+HexUtil.bytesToHex(realHash)+" ("+(length-HASH_LENGTH)+" bytes payload)");
            return false;
        }
        
        // Verify
        tracker.pn.verified(tracker);
        
        for(int i=0;i<md.getDigestLength();i++) {
            packetHash[i] ^= buf[offset+i];
        }
        if(logMINOR) Logger.minor(this, "Contributing entropy");
        node.random.acceptEntropyBytes(myPacketDataSource, packetHash, 0, md.getDigestLength(), 0.5);
        if(logMINOR) Logger.minor(this, "Contributed entropy");
        
        // Lots more to do yet!
        processDecryptedData(plaintext, seqNumber, tracker, length - plaintext.length);
        tracker.pn.reportIncomingBytes(length);
        return true;
    }

    /**
     * Process an incoming packet, once it has been decrypted.
     * @param decrypted The packet's contents.
     * @param seqNumber The detected sequence number of the packet.
     * @param tracker The KeyTracker responsible for the key used to encrypt the packet.
     */
    private void processDecryptedData(byte[] decrypted, int seqNumber, KeyTracker tracker, int overhead) {
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
        
        if(logMINOR) Logger.minor(this, "Reference sequence number: "+referenceSeqNumber);

        int ackCount = decrypted[ptr++] & 0xff;
        if(logMINOR) Logger.minor(this, "Acks: "+ackCount);

        int[] acks = new int[ackCount];
        for(int i=0;i<ackCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
                return;
            }
            acks[i] = referenceSeqNumber - offset;
        }
        
        tracker.acknowledgedPackets(acks);
        
        int retransmitCount = decrypted[ptr++] & 0xff;
        if(logMINOR) Logger.minor(this, "Retransmit requests: "+retransmitCount);
        
        for(int i=0;i<retransmitCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = referenceSeqNumber - offset;
            if(logMINOR) Logger.minor(this, "RetransmitRequest: "+realSeqNo);
            tracker.resendPacket(realSeqNo);
        }

        int ackRequestsCount = decrypted[ptr++] & 0xff;
        if(logMINOR) Logger.minor(this, "Ack requests: "+ackRequestsCount);
        
        // These two are relative to our outgoing packet number
        // Because they relate to packets we have sent.
        for(int i=0;i<ackRequestsCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = realSeqNumber - offset;
            if(logMINOR) Logger.minor(this, "AckRequest: "+realSeqNo);
            tracker.receivedAckRequest(realSeqNo);
        }
        
        int forgottenCount = decrypted[ptr++] & 0xff;
        if(logMINOR) Logger.minor(this, "Forgotten packets: "+forgottenCount);
        
        for(int i=0;i<forgottenCount;i++) {
            int offset = decrypted[ptr++] & 0xff;
            if(ptr > decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int realSeqNo = realSeqNumber - offset;
            tracker.destForgotPacket(realSeqNo);
        }

        if(seqNumber == -1) {
        	Logger.minor(this, "Returning because seqno = "+seqNumber);
        	return;
        }
        // No sequence number == no messages

        if((seqNumber != -1) && tracker.alreadyReceived(seqNumber)) {
            tracker.queueAck(seqNumber);
			tracker.pn.receivedPacket(false);
            Logger.error(this, "Received packet twice ("+seqNumber+") from "+tracker.pn.getPeer()+": "+seqNumber+" ("+TimeUtil.formatTime((long) tracker.pn.pingAverage.currentValue(), 2, true)+" ping avg)");
            return;
        }
        
        tracker.receivedPacket(seqNumber);
        
        int messages = decrypted[ptr++] & 0xff;
        
        overhead += ptr;
        
        for(int i=0;i<messages;i++) {
            if(ptr+1 >= decrypted.length) {
                Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
            }
            int length = ((decrypted[ptr++] & 0xff) << 8) +
            	(decrypted[ptr++] & 0xff);
            if(length > decrypted.length - ptr) {
                Logger.error(this, "Message longer than remaining space: "+length);
                return;
            }
            if(logMINOR) Logger.minor(this, "Message "+i+" length "+length+", hash code: "+Fields.hashCode(decrypted, ptr, length));
            Message m = usm.decodeSingleMessage(decrypted, ptr, length, tracker.pn, 1 + (overhead / messages));
            ptr+=length;
            if(m != null) {
                //Logger.minor(this, "Dispatching packet: "+m);
                usm.checkFilters(m);
            }
        }
        if(logMINOR) Logger.minor(this, "Done");
    }

    /**
     * Build a packet and send it, from a whole bunch of messages.
     */
    public void processOutgoingOrRequeue(MessageItem[] messages, PeerNode pn, boolean neverWaitForPacketNumber, boolean dontRequeue) {
    	String requeueLogString = "";
    	if(!dontRequeue) {
    		requeueLogString = ", requeueing";
    	}
        if(logMINOR) Logger.minor(this, "processOutgoingOrRequeue "+messages.length+" messages for "+pn+" ("+neverWaitForPacketNumber+ ')');
        byte[][] messageData = new byte[messages.length][];
        int[] alreadyReported = new int[messages.length];
        MessageItem[] newMsgs = new MessageItem[messages.length];
        KeyTracker kt = pn.getCurrentKeyTracker();
        int length = 1;
        length += kt.countAcks() + kt.countAckRequests() + kt.countResendRequests();
        int callbacksCount = 0;
        int x = 0;
		String mi_name = null;
        for(int i=0;i<messageData.length;i++) {
            MessageItem mi = messages[i];
        	if(logMINOR) Logger.minor(this, "Handling formatted MessageItem "+mi+" : "+mi.getData(pn).length);
			mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
            if(mi.formatted) {
                try {
                    byte[] buf = mi.getData(pn);
                    kt = pn.getCurrentKeyTracker();
                    if(kt == null) {
                        if(logMINOR) Logger.minor(this, "kt = null");
                        pn.requeueMessageItems(messages, i, messages.length-i, false, "kt = null");
                        return;
                    }
                    int packetNumber = kt.allocateOutgoingPacketNumberNeverBlock();
                    this.processOutgoingPreformatted(buf, 0, buf.length, pn.getCurrentKeyTracker(), packetNumber, mi.cb, mi.alreadyReportedBytes);
                    if(mi.ctrCallback != null)
                    	mi.ctrCallback.sentBytes(buf.length + HEADERS_LENGTH_ONE_MESSAGE);
                } catch (NotConnectedException e) {
                    Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
                    // Requeue
                    if(!dontRequeue) {
                    	pn.requeueMessageItems(messages, 0, x, false, "NotConnectedException(1a)");
                    	pn.requeueMessageItems(messages, i, messages.length-i, false, "NotConnectedException(1b)");
                    }
                    return;
                } catch (WouldBlockException e) {
                    if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                    // Requeue
                    if(!dontRequeue) {
                    	pn.requeueMessageItems(messages, 0, x, false, "WouldBlockException(1a)");
                    	pn.requeueMessageItems(messages, i, messages.length-i, false, "WouldBlockException(1b)");
                    }
                    return;
                } catch (KeyChangedException e) {
                	if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                    // Requeue
                    if(!dontRequeue) {
                    	pn.requeueMessageItems(messages, 0, x, false, "KeyChangedException(1a)");
                    	pn.requeueMessageItems(messages, i, messages.length-i, false, "KeyChangedException(1b)");
                    }
                    return;
                } catch (Throwable e) {
                    Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                    // Requeue
                    if(!dontRequeue) {
                    	pn.requeueMessageItems(messages, 0, x, false, "Throwable(1)");
                    	pn.requeueMessageItems(messages, i, messages.length-i, false, "Throwable(1)");
                    }
                    return;
                }
            } else {
                byte[] data = mi.getData(pn);
                messageData[x] = data;
                if(data.length > node.usm.getMaxPacketSize()) {
                    Logger.error(this, "Message exceeds packet size: "+messages[i]+" size "+data.length+" message "+mi.msg);
                    // Will be handled later
                }
                newMsgs[x] = mi;
                alreadyReported[x] = mi.alreadyReportedBytes;
                x++;
                if(mi.cb != null) callbacksCount += mi.cb.length;
                if(logMINOR) Logger.minor(this, "Sending: "+mi+" length "+data.length+" cb "+mi.cb);
                length += (data.length + 2);
            }
        }
        if(x != messageData.length) {
            byte[][] newMessageData = new byte[x][];
            System.arraycopy(messageData, 0, newMessageData, 0, x);
            messageData = newMessageData;
            messages = newMsgs;
            newMsgs = new MessageItem[x];
            System.arraycopy(messages, 0, newMsgs, 0, x);
            messages = newMsgs;
        }
        AsyncMessageCallback callbacks[] = new AsyncMessageCallback[callbacksCount];
        x=0;
        int alreadyReportedBytes = 0;
        for(int i=0;i<messages.length;i++) {
            if(messages[i].formatted) continue;
            if(messages[i].cb != null) {
            	alreadyReportedBytes += messages[i].alreadyReportedBytes;
                System.arraycopy(messages[i].cb, 0, callbacks, x, messages[i].cb.length);
                x += messages[i].cb.length;
            }
        }
        if(x != callbacksCount) throw new IllegalStateException();
        
        if((length + HEADERS_LENGTH_MINIMUM < node.usm.getMaxPacketSize()) &&
                (messageData.length < 256)) {
			mi_name = null;
            try {
                innerProcessOutgoing(messageData, 0, messageData.length, length, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
                for(int i=0;i<messageData.length;i++) {
                	MessageItem mi = newMsgs[i];
					mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
                	if(mi.ctrCallback != null) {
                		mi.ctrCallback.sentBytes(messageData[i].length + 
                				1 + (HEADERS_LENGTH_MINIMUM / messageData.length));
                		// FIXME rounding issues
                	}
                }
            } catch (NotConnectedException e) {
                Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
                // Requeue
                if(!dontRequeue)
                	pn.requeueMessageItems(messages, 0, messages.length, false, "NotConnectedException(2)");
                return;
            } catch (WouldBlockException e) {
            	if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                // Requeue
                if(!dontRequeue)
                	pn.requeueMessageItems(messages, 0, messages.length, false, "WouldBlockException(2)");
                return;
            } catch (Throwable e) {
                Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                // Requeue
                if(!dontRequeue)
                	pn.requeueMessageItems(messages, 0, messages.length, false, "Throwable(2)");
                return;
                
            }
        } else {
			if(!dontRequeue) {
				requeueLogString = ", requeueing remaining messages";
			}
            length = 1;
            length += kt.countAcks() + kt.countAckRequests() + kt.countResendRequests();
            int count = 0;
            int lastIndex = 0;
            alreadyReportedBytes = 0;
            for(int i=0;i<=messageData.length;i++) {
                int thisLength;
                if(i == messages.length) thisLength = 0;
                else thisLength = (messageData[i].length + 2);
                int newLength = length + thisLength;
                count++;
                if((newLength + HEADERS_LENGTH_MINIMUM > node.usm.getMaxPacketSize()) || (count > 255) || (i == messages.length)) {
                    // lastIndex up to the message right before this one
                    // e.g. lastIndex = 0, i = 1, we just send message 0
                    if(lastIndex != i) {
						mi_name = null;
                        try {
                            innerProcessOutgoing(messageData, lastIndex, i-lastIndex, length, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
                            for(int j=lastIndex;j<i;j++) {
                            	MessageItem mi = newMsgs[j];
								mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
                            	if(mi.ctrCallback != null) {
                            		mi.ctrCallback.sentBytes(messageData[j].length + 
                            				1 + (HEADERS_LENGTH_MINIMUM / (i-lastIndex)));
                            		// FIXME rounding issues
                            	}
                            }
                        } catch (NotConnectedException e) {
                            Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
                            // Requeue
                            if(!dontRequeue)
                            	pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "NotConnectedException(3)");
                            return;
                        } catch (WouldBlockException e) {
                        	if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                            // Requeue
                            if(!dontRequeue)
                            	pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "WouldBlockException(3)");
                            return;
                        } catch (Throwable e) {
                            Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
                            // Requeue
                            if(!dontRequeue)
                            	pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "Throwable(3)");
                            return;
                        }
                    }
                    lastIndex = i;
                    if(i != messageData.length)
                        length = 1 + (messageData[i].length + 2);
                    count = 0;
                } else {
                	length = newLength;
                	alreadyReportedBytes += alreadyReported[i];
                }
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
     * @throws PacketSequenceException 
     */
    private void innerProcessOutgoing(byte[][] messageData, int start, int length, int bufferLength, 
    		PeerNode pn, boolean neverWaitForPacketNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws NotConnectedException, WouldBlockException, PacketSequenceException {
    	if(logMINOR) Logger.minor(this, "innerProcessOutgoing(...,"+start+ ',' +length+ ',' +bufferLength+ ')');
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
        processOutgoingPreformatted(buf, 0, loc, pn, neverWaitForPacketNumber, callbacks, alreadyReportedBytes);
    }

    /**
     * Build a packet and send it. From a Message recently converted into byte[],
     * but with no outer formatting.
     * @throws PacketSequenceException 
     * @throws WouldBlockException 
     */
    public void processOutgoing(byte[] buf, int offset, int length, KeyTracker tracker, int alreadyReportedBytes) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
        byte[] newBuf = preformat(buf, offset, length);
        processOutgoingPreformatted(newBuf, 0, newBuf.length, tracker, -1, null, alreadyReportedBytes);
    }
    
    /**
     * Send a packet using the current key. Retry if it fails solely because
     * the key changes.
     * @throws PacketSequenceException 
     */
    void processOutgoingPreformatted(byte[] buf, int offset, int length, PeerNode peer, boolean neverWaitForPacketNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws NotConnectedException, WouldBlockException, PacketSequenceException {
    	KeyTracker last = null;
        while(true) {
            try {
            	if(!peer.isConnected())
            		throw new NotConnectedException();
                KeyTracker tracker = peer.getCurrentKeyTracker();
                last = tracker;
                if(tracker == null) {
                    Logger.normal(this, "Dropping packet: Not connected to "+peer.getPeer()+" yet(2)");
                    throw new NotConnectedException();
                }
                int seqNo = neverWaitForPacketNumber ? tracker.allocateOutgoingPacketNumberNeverBlock() :
                    tracker.allocateOutgoingPacketNumber();
                processOutgoingPreformatted(buf, offset, length, tracker, seqNo, callbacks, alreadyReportedBytes);
                return;
            } catch (KeyChangedException e) {
            	Logger.normal(this, "Key changed(2) for "+peer.getPeer());
            	if(last == peer.getCurrentKeyTracker()) {
            		if(peer.isConnected()) {
            			Logger.error(this, "Peer is connected, yet current tracker is deprecated !!: "+e, e);
            			throw new NotConnectedException("Peer is connected, yet current tracker is deprecated !!: "+e);
            		}
            	}
                // Go around again
            }
        }
    }

    public byte[] preformat(Message msg, PeerNode pn) {
        byte[] buf = msg.encodeToPacket(pn);
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
     * @throws PacketSequenceException 
     * @throws WouldBlockException If we cannot allocate a packet number because it would block.
     */
    public void processOutgoingPreformatted(byte[] buf, int offset, int length, KeyTracker tracker, int packetNumber, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
        if(logMINOR) {
            String log = "processOutgoingPreformatted("+Fields.hashCode(buf)+", "+offset+ ',' +length+ ',' +tracker+ ',' +packetNumber+ ',';
            if(callbacks == null) log += "null";
            else log += (""+callbacks.length+(callbacks.length >= 1 ? String.valueOf(callbacks[0]) : ""));
            Logger.minor(this, log);
        }
        if((tracker == null) || (!tracker.pn.isConnected())) {
            throw new NotConnectedException();
        }
        
        // We do not support forgotten packets at present

        int[] acks, resendRequests, ackRequests;
    	int seqNumber;
        /* Locking:
         * Avoid allocating a packet number, then a long pause due to 
         * overload, during which many other packets are sent, 
         * resulting in the other side asking us to resend a packet 
         * which doesn't exist yet.
         * => grabbing resend reqs, packet no etc must be as
         * close together as possible.
         * 
         * HOWEVER, tracker.allocateOutgoingPacketNumber can block,
         * so should not be locked.
         */
    	
       	if(packetNumber > 0)
       		seqNumber = packetNumber;
       	else {
       		if(buf.length == 1)
       			// Ack/resendreq only packet
       			seqNumber = -1;
       		else
       			seqNumber = tracker.allocateOutgoingPacketNumberNeverBlock();
       	}
        
       	if(logMINOR) Logger.minor(this, "Sequence number (sending): "+seqNumber+" ("+packetNumber+") to "+tracker.pn.getPeer());
        
        /** The last sent sequence number, so that we can refer to packets
         * sent after this packet was originally sent (it may be a resend) */
       	int realSeqNumber;
       	
       	int otherSideSeqNumber;
       	
       	synchronized(tracker) {
        	acks = tracker.grabAcks();
        	resendRequests = tracker.grabResendRequests();
        	ackRequests = tracker.grabAckRequests();
            realSeqNumber = tracker.getLastOutgoingSeqNumber();
            otherSideSeqNumber = tracker.highestReceivedIncomingSeqNumber();
            if(logMINOR) Logger.minor(this, "Sending packet to "+tracker.pn.getPeer()+", other side max seqno: "+otherSideSeqNumber);
        }
        
        int packetLength = 4 + // seq number
        	RANDOM_BYTES_LENGTH + // random junk
        	1 + // version
        	((packetNumber == -1) ? 4 : 1) + // highest sent seqno - 4 bytes if seqno = -1
        	4 + // other side's seqno
        	1 + // number of acks
        	acks.length + // acks
        	1 + // number of resend reqs
        	resendRequests.length + // resend requests
        	1 + // number of ack requests
        	ackRequests.length + // ack requests
        	1 + // no forgotten packets
        	length; // the payload !
        
        if(logMINOR) Logger.minor(this, "Packet length: "+packetLength+" ("+length+")");

        byte[] plaintext = new byte[packetLength];
        
        byte[] randomJunk = new byte[RANDOM_BYTES_LENGTH];
        
        int ptr = offset;

        plaintext[ptr++] = (byte)(seqNumber >> 24);
        plaintext[ptr++] = (byte)(seqNumber >> 16);
        plaintext[ptr++] = (byte)(seqNumber >> 8);
        plaintext[ptr++] = (byte)seqNumber;
        
        if(logMINOR) Logger.minor(this, "Getting random junk");
        node.random.nextBytes(randomJunk);
        System.arraycopy(randomJunk, 0, plaintext, ptr, RANDOM_BYTES_LENGTH);
        ptr += RANDOM_BYTES_LENGTH;
        
        plaintext[ptr++] = 0; // version number

        if(seqNumber == -1) {
            plaintext[ptr++] = (byte)(realSeqNumber >> 24);
            plaintext[ptr++] = (byte)(realSeqNumber >> 16);
            plaintext[ptr++] = (byte)(realSeqNumber >> 8);
            plaintext[ptr++] = (byte)realSeqNumber;
        } else {
            plaintext[ptr++] = (byte)(realSeqNumber - seqNumber);
        }
        
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 24);
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 16);
        plaintext[ptr++] = (byte)(otherSideSeqNumber >> 8);
        plaintext[ptr++] = (byte)otherSideSeqNumber;
        
        plaintext[ptr++] = (byte) acks.length;
        for(int i=0;i<acks.length;i++) {
            int ackSeq = acks[i];
            if(logMINOR) Logger.minor(this, "Acking "+ackSeq);
            int offsetSeq = otherSideSeqNumber - ackSeq;
            if((offsetSeq > 255) || (offsetSeq < 0))
                throw new PacketSequenceException("bad ack offset "+offsetSeq+
                        " - seqNumber="+otherSideSeqNumber+", ackNumber="+ackSeq+" talking to "+tracker.pn.getPeer());
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        plaintext[ptr++] = (byte) resendRequests.length;
        for(int i=0;i<resendRequests.length;i++) {
            int reqSeq = resendRequests[i];
            if(logMINOR) Logger.minor(this, "Resend req: "+reqSeq);
            int offsetSeq = otherSideSeqNumber - reqSeq;
            if((offsetSeq > 255) || (offsetSeq < 0))
                throw new PacketSequenceException("bad resend request offset "+offsetSeq+
                        " - reqSeq="+reqSeq+", otherSideSeqNumber="+otherSideSeqNumber+" talking to "+tracker.pn.getPeer());
            plaintext[ptr++] = (byte)offsetSeq;
        }

        plaintext[ptr++] = (byte) ackRequests.length;
        if(logMINOR) Logger.minor(this, "Ackrequests: "+ackRequests.length);
        for(int i=0;i<ackRequests.length;i++) {
            int ackReqSeq = ackRequests[i];
            if(logMINOR) Logger.minor(this, "Ack request "+i+": "+ackReqSeq);
            // Relative to packetNumber - we are asking them to ack
            // a packet we sent to them.
            int offsetSeq = realSeqNumber - ackReqSeq;
            if((offsetSeq > 255) || (offsetSeq < 0))
                throw new PacketSequenceException("bad ack requests offset: "+offsetSeq+
                        " - ackReqSeq="+ackReqSeq+", packetNumber="+realSeqNumber+" talking to "+tracker.pn.getPeer());
            plaintext[ptr++] = (byte)offsetSeq;
        }
        
        // No forgotten packets
        plaintext[ptr++] = 0;

        System.arraycopy(buf, offset, plaintext, ptr, length);
        ptr += length;
        
        if(ptr != plaintext.length) {
        	Logger.error(this, "Inconsistent length: "+plaintext.length+" buffer but "+(ptr)+" actual");
        	byte[] newBuf = new byte[ptr];
        	System.arraycopy(plaintext, 0, newBuf, 0, ptr);
        	plaintext = newBuf;
        }

        if(seqNumber != -1) {
            byte[] saveable = new byte[length];
            System.arraycopy(buf, offset, saveable, 0, length);
            tracker.sentPacket(saveable, seqNumber, callbacks);
        }
        
        if(logMINOR) Logger.minor(this, "Sending...");

        processOutgoingFullyFormatted(plaintext, tracker, callbacks, alreadyReportedBytes);
        if(logMINOR) Logger.minor(this, "Sent packet");
    }

    /**
     * Encrypt and send a packet.
     * @param plaintext The packet's plaintext, including all formatting,
     * including acks and resend requests. Is clobbered.
     */
    private void processOutgoingFullyFormatted(byte[] plaintext, KeyTracker kt, AsyncMessageCallback[] callbacks, int alreadyReportedBytes) {
        BlockCipher sessionCipher = kt.sessionCipher;
        if(logMINOR) Logger.minor(this, "Encrypting with "+HexUtil.bytesToHex(kt.sessionKey));
        if(sessionCipher == null) {
            Logger.error(this, "Dropping packet send - have not handshaked yet");
            return;
        }
        int blockSize = sessionCipher.getBlockSize() >> 3;
        if(sessionCipher.getKeySize() != sessionCipher.getBlockSize())
            throw new IllegalStateException("Block size must be half key size: blockSize="+
                    sessionCipher.getBlockSize()+", keySize="+sessionCipher.getKeySize());
        
        MessageDigest md = SHA256.getMessageDigest();
        
        int digestLength = md.getDigestLength();
        
        if(digestLength != blockSize)
            throw new IllegalStateException("Block size must be digest length!");
        
        byte[] output = new byte[plaintext.length + digestLength];
        System.arraycopy(plaintext, 0, output, digestLength, plaintext.length);
        
        md.update(plaintext);
        
        //Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));
        
        byte[] digestTemp;
        
        digestTemp = md.digest();

        if(logMINOR) Logger.minor(this, "\nHash:      "+HexUtil.bytesToHex(digestTemp));
                
        // Put encrypted digest in output
        sessionCipher.encipher(digestTemp, digestTemp);
        
        // Now copy it back
        System.arraycopy(digestTemp, 0, output, 0, digestLength);
        // Yay, we have an encrypted hash

        if(logMINOR) Logger.minor(this, "\nEncrypted: "+HexUtil.bytesToHex(digestTemp)+" ("+plaintext.length+" bytes plaintext)");
        
        PCFBMode pcfb = new PCFBMode(sessionCipher, digestTemp);
        pcfb.blockEncipher(output, digestLength, plaintext.length);
        
        //Logger.minor(this, "Ciphertext:\n"+HexUtil.bytesToHex(output, digestLength, plaintext.length));
        
        // We have a packet
        // Send it
        
        if(logMINOR) Logger.minor(this,"Sending packet of length "+output.length+" (" + Fields.hashCode(output) + " to "+kt.pn);
        
        // pn.getPeer() cannot be null
        try {
        	sendPacket(output, kt.pn.getPeer(), kt.pn, alreadyReportedBytes);
		} catch (LocalAddressException e) {
			Logger.error(this, "Tried to send data packet to local address: "+kt.pn.getPeer()+" for "+kt.pn.allowLocalAddresses());
		}
        kt.pn.sentPacket();
    }

    /**
     * Send a handshake, if possible, to the node.
     * @param pn
     */
    public void sendHandshake(PeerNode pn) {
    	if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn);
        DiffieHellmanContext ctx;
        Peer[] handshakeIPs;
        if(!pn.shouldSendHandshake()) {
        	if(logMINOR) Logger.minor(this, "Not sending handshake to "+pn.getPeer()+" because pn.shouldSendHandshake() returned false");
        	return;
        }
        long firstTime = System.currentTimeMillis();
        handshakeIPs = pn.getHandshakeIPs();
        long secondTime = System.currentTimeMillis();
        if((secondTime - firstTime) > 1000)
            Logger.error(this, "getHandshakeIPs() took more than a second to execute ("+(secondTime - firstTime)+") working on "+pn.getName());
        if(handshakeIPs.length == 0) {
            pn.couldNotSendHandshake();
            long thirdTime = System.currentTimeMillis();
            if((thirdTime - secondTime) > 1000)
                Logger.error(this, "couldNotSendHandshake() (after getHandshakeIPs()) took more than a second to execute ("+(thirdTime - secondTime)+") working on "+pn.getName());
            return;
        } else {
            long DHTime1 = System.currentTimeMillis();
            ctx = DiffieHellman.generateContext();
            long DHTime2 = System.currentTimeMillis();
            if((DHTime2 - DHTime1) > 1000)
                Logger.error(this, "DHTime2 is more than a second after DHTime1 ("+(DHTime2 - DHTime1)+") working on "+pn.getName());
            pn.setKeyAgreementSchemeContext(ctx);
            long DHTime3 = System.currentTimeMillis();
            if((DHTime3 - DHTime2) > 1000)
                Logger.error(this, "DHTime3 is more than a second after DHTime2 ("+(DHTime3 - DHTime2)+") working on "+pn.getName());
        }
        int sentCount = 0;
        long loopTime1 = System.currentTimeMillis();
        for(int i=0;i<handshakeIPs.length;i++){
        	long innerLoopTime1 = System.currentTimeMillis();
        	if(handshakeIPs[i].getAddress(false) == null) {
        		if(logMINOR) Logger.minor(this, "Not sending handshake to "+handshakeIPs[i]+" for "+pn.getPeer()+" because the DNS lookup failed or it's a currently unsupported IPv6 address");
        		continue;
        	}
        	if(!pn.allowLocalAddresses() && !handshakeIPs[i].isRealInternetAddress(false, false)) {
        		if(logMINOR) Logger.minor(this, "Not sending handshake to "+handshakeIPs[i]+" for "+pn.getPeer()+" because it's not a real Internet address and metadata.allowLocalAddresses is not true");
        		continue;
        	}
        	long innerLoopTime2 = System.currentTimeMillis();
        	if((innerLoopTime2 - innerLoopTime1) > 500)
        		Logger.normal(this, "innerLoopTime2 is more than half a second after innerLoopTime1 ("+(innerLoopTime2 - innerLoopTime1)+") working on "+handshakeIPs[i]+" of "+pn.getName());
        	sendFirstHalfDHPacket(0, ctx.getOurExponential(), pn, handshakeIPs[i]);
        	long innerLoopTime3 = System.currentTimeMillis();
        	if((innerLoopTime3 - innerLoopTime2) > 500)
        		Logger.normal(this, "innerLoopTime3 is more than half a second after innerLoopTime2 ("+(innerLoopTime3 - innerLoopTime2)+") working on "+handshakeIPs[i]+" of "+pn.getName());
        	pn.sentHandshake();
        	long innerLoopTime4 = System.currentTimeMillis();
        	if((innerLoopTime4 - innerLoopTime3) > 500)
        		Logger.normal(this, "innerLoopTime4 is more than half a second after innerLoopTime3 ("+(innerLoopTime4 - innerLoopTime3)+") working on "+handshakeIPs[i]+" of "+pn.getName());
        	sentCount += 1;
        }
        long loopTime2 = System.currentTimeMillis();
        if((loopTime2 - loopTime1) > 1000)
        	Logger.normal(this, "loopTime2 is more than a second after loopTime1 ("+(loopTime2 - loopTime1)+") working on "+pn.getName());
        if(sentCount==0) {
            pn.couldNotSendHandshake();
        }
    }

    public boolean isDisconnected(PeerContext context) {
        if(context == null) return false;
        return !((PeerNode)context).isConnected();
    }
    
    /**
     * Send a first-half (phase 0 or 1) StS negotiation packet to the node.
     * @param phase The phase of the message to be sent (0 or 1).
     * @param integer
     * @param replyTo
     */
    private void sendFirstStSPacket(int phase, NativeBigInteger integer, PeerNode pn, Peer replyTo) {
        if(logMINOR) Logger.minor(this, "Sending ("+phase+") "+integer.toHexString()+" to "+pn.getPeer());
        byte[] data = integer.toByteArray();

        sendAuthPacket(1, 1, phase, data, pn, replyTo);
    }
    
    private void sendSecondStSPacket(int phase, StationToStationContext ctx, PeerNode pn, Peer replyTo, byte[] data) {
    	NativeBigInteger hisExponent = new NativeBigInteger(data);
    	ctx.setOtherSideExponential(hisExponent);
    	
        sendAuthPacket(1, 1, phase, ctx.concatAndSignAndCrypt(), pn, replyTo);
    }
}
