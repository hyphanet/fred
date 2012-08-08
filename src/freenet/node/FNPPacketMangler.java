/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import freenet.clients.http.ExternalLinkToadlet;
import net.i2p.util.NativeBigInteger;
import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSASignature;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanLightContext;
import freenet.crypt.EntropySource;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker;
import freenet.io.AddressTracker.Status;
import freenet.io.comm.AsyncMessageCallback;
import freenet.io.comm.DMT;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IncomingPacketFilter.DECODED;
import freenet.io.comm.MessageCore;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.SocketHandler;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.l10n.NodeL10n;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.ByteArrayWrapper;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.LRUHashtable;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.WouldBlockException;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * @author amphibian
 *
 * Encodes and decodes packets for FNP.
 *
 * This includes encryption, authentication, and may later
 * include queueing etc. (that may require some interface
 * changes in IncomingPacketFilter).
 */
public class FNPPacketMangler implements OutgoingPacketMangler {
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private final Node node;
	private final NodeCrypto crypto;
	private final PacketSocketHandler sock;
	private final EntropySource myPacketDataSource;
	/**
	 * Objects cached during JFK message exchange: JFK(3,4) with authenticator as key
	 * The messages are cached in hashmaps because the message retrieval from the cache
	 * can be performed in constant time( given the key)
	 */
	private final HashMap<ByteArrayWrapper, byte[]> authenticatorCache;
	/** The following is used in the HMAC calculation of JFK message3 and message4 */
	private static final byte[] JFK_PREFIX_INITIATOR, JFK_PREFIX_RESPONDER;
	static {
		byte[] I = null,R = null;
		try {
			I = "I".getBytes("UTF-8");
			R = "R".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		JFK_PREFIX_INITIATOR = I;
		JFK_PREFIX_RESPONDER = R;
	}

	/* How often shall we generate a new exponential and add it to the FIFO? */
	public final static int DH_GENERATION_INTERVAL = 30000; // 30sec
	/* How big is the FIFO? */
	public final static int DH_CONTEXT_BUFFER_SIZE = 20;
	/*
	* The FIFO itself
	* Get a lock on dhContextFIFO before touching it!
	*/
	private final LinkedList<DiffieHellmanLightContext> dhContextFIFO = new LinkedList<DiffieHellmanLightContext>();
	/* The element which is about to be prunned from the FIFO */
	private DiffieHellmanLightContext dhContextToBePrunned = null;
	private long jfkDHLastGenerationTimestamp = 0;

	protected static final int NONCE_SIZE = 8;
	private static final int MAX_PACKETS_IN_FLIGHT = 256;
	private static final int RANDOM_BYTES_LENGTH = 12;
	private static final int HASH_LENGTH = SHA256.getDigestLength();
	/** The size of the key used to authenticate the hmac */
	private static final int TRANSIENT_KEY_SIZE = HASH_LENGTH;
	/** The key used to authenticate the hmac */
	private final byte[] transientKey = new byte[TRANSIENT_KEY_SIZE];
	public static final int TRANSIENT_KEY_REKEYING_MIN_INTERVAL = 30*60*1000;
	/** The rekeying interval for the session key (keytrackers) */
	public static final int SESSION_KEY_REKEYING_INTERVAL = 60*60*1000;
	/** The max amount of time we will accept to use the current tracker when it should have been replaced */
	public static final int MAX_SESSION_KEY_REKEYING_DELAY = 5*60*1000;
	/** The amount of data sent before we ask for a rekey */
	public static final int AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY = 1024 * 1024 * 1024;
	/** The Runnable in charge of rekeying on a regular basis */
	private final Runnable transientKeyRekeyer = new Runnable() {
		@Override
		public void run() {
			maybeResetTransientKey();
		}
	};
	/** Minimum headers overhead */
	private static final int HEADERS_LENGTH_MINIMUM =
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
	static public final int HEADERS_LENGTH_ONE_MESSAGE =
		HEADERS_LENGTH_MINIMUM + 2; // 2 bytes = length of message. rest is the same.

	final int fullHeadersLengthMinimum;
	final int fullHeadersLengthOneMessage;
        
        private long lastConnectivityStatusUpdate;
        private Status lastConnectivityStatus;


	public FNPPacketMangler(Node node, NodeCrypto crypt, PacketSocketHandler sock) {
		this.node = node;
		this.crypto = crypt;
		this.sock = sock;
		myPacketDataSource = new EntropySource();
		authenticatorCache = new HashMap<ByteArrayWrapper, byte[]>();
		fullHeadersLengthMinimum = HEADERS_LENGTH_MINIMUM + sock.getHeadersLength();
		fullHeadersLengthOneMessage = HEADERS_LENGTH_ONE_MESSAGE + sock.getHeadersLength();
	}

	/**
	 * Start up the FNPPacketMangler. By the time this is called, all objects will have been constructed,
	 * but not all will have been started yet.
	 */
	public void start() {
		// Run it directly so that the transient key is set.
		maybeResetTransientKey();
		// Fill the DH FIFO on-thread
		for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++) {
			_fillJFKDHFIFO();
		}
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

	public DECODED process(byte[] buf, int offset, int length, Peer peer, long now) {
		/**
		 * Look up the Peer.
		 * If we know it, check the packet with that key.
		 * Otherwise try all of them (on the theory that nodes
		 * occasionally change their IP addresses).
		 */
		PeerNode opn = node.peers.getByPeer(peer);
		return process(buf, offset, length, peer, opn, now);
	}

	/**
	 * Decrypt and authenticate packet.
	 * Then feed it to USM.checkFilters.
	 * Packets generated should have a PeerNode on them.
	 * Note that the buffer can be modified by this method.
	 */
	public DECODED process(byte[] buf, int offset, int length, Peer peer, PeerNode opn, long now) {

		if(opn != null && opn.getOutgoingMangler() != this) {
			Logger.error(this, "Apparently contacted by "+opn+") on "+this, new Exception("error"));
			opn = null;
		}
		PeerNode pn;
		boolean wantAnonAuth = crypto.wantAnonAuth();

		if(opn != null) {
			if(logMINOR) Logger.minor(this, "Trying exact match");
			if(length > HEADERS_LENGTH_MINIMUM) {
				if(logMINOR) Logger.minor(this, "Trying current key tracker for exact match");
				if(tryProcess(buf, offset, length, opn.getCurrentKeyTracker(), now)) {
					return DECODED.DECODED;
				}
				// Try with old key
				if(logMINOR) Logger.minor(this, "Trying previous key tracker for exact match");
				if(tryProcess(buf, offset, length, opn.getPreviousKeyTracker(), now)) {
					return DECODED.DECODED;
				}
				// Try with unverified key
				if(logMINOR) Logger.minor(this, "Trying unverified key tracker for exact match");
				if(tryProcess(buf, offset, length, opn.getUnverifiedKeyTracker(), now)) {
					return DECODED.DECODED;
				}
			}
			if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2 && !node.isStopping()) {
				// Might be an auth packet
				if(tryProcessAuth(buf, offset, length, opn, peer, false, now)) {
					return DECODED.DECODED;
				}
				// Might be a reply to us sending an anon auth packet.
				// I.e. we are not the seednode, they are.
				if(tryProcessAuthAnonReply(buf, offset, length, opn, peer, now)) {
					return DECODED.DECODED;
				}
			}
		}
		PeerNode[] peers = crypto.getPeerNodes();
		// Existing connection, changed IP address?
		if(length > HASH_LENGTH + RANDOM_BYTES_LENGTH + 4 + 6) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(logDEBUG) Logger.debug(this, "Trying current key tracker for loop");
				if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return DECODED.DECODED;
				}
				if(logDEBUG) Logger.debug(this, "Trying previous key tracker for loop");
				if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return DECODED.DECODED;
				}
				if(logDEBUG) Logger.debug(this, "Trying unverified key tracker for loop");
				if(tryProcess(buf, offset, length, pn.getUnverifiedKeyTracker(), now)) {
					// IP address change
					pn.changedIP(peer);
					return DECODED.DECODED;
				}
			}
		}
		if(node.isStopping()) return DECODED.SHUTTING_DOWN;
		// Disconnected node connecting on a new IP address?
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				if(pn == opn) continue;
				if(logDEBUG)
					Logger.debug(this, "Trying auth with "+pn);
				if(tryProcessAuth(buf, offset, length, pn, peer,false, now)) {
					return DECODED.DECODED;
				}
				if(pn.handshakeUnknownInitiator()) {
					// Might be a reply to us sending an anon auth packet.
					// I.e. we are not the seednode, they are.
					if(tryProcessAuthAnonReply(buf, offset, length, pn, peer, now)) {
						return DECODED.DECODED;
					}
				}
			}
		}
		
		boolean wantAnonAuthChangeIP = wantAnonAuth && crypto.wantAnonAuthChangeIP();
		
		if(wantAnonAuth && wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(opn, buf, offset, length, peer, now)) return DECODED.DECODED;
		}

		boolean didntTryOldOpennetPeers;
		OpennetManager opennet = node.getOpennet();
		if(opennet != null) {
			// Try old opennet connections.
			if(opennet.wantPeer(null, false, true, true, ConnectionType.RECONNECT)) {
				// We want a peer.
				// Try old connections.
				PeerNode[] oldPeers = opennet.getOldPeers();
				for(int i=0;i<oldPeers.length;i++) {
					if(tryProcessAuth(buf, offset, length, oldPeers[i], peer, true, now)) return DECODED.DECODED;
				}
				didntTryOldOpennetPeers = false;
			} else
				didntTryOldOpennetPeers = true;
		} else
			didntTryOldOpennetPeers = false;
		if(wantAnonAuth) {
			if(tryProcessAuthAnon(buf, offset, length, peer))
				return DECODED.DECODED;
		}
		
		if(wantAnonAuth && !wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(opn, buf, offset, length, peer, now)) {
				// This can happen when a node is upgraded from a SeedClientPeerNode to an OpennetPeerNode.
				//Logger.error(this, "Last resort match anon-auth against all anon setup peernodes succeeded - this should not happen! (It can happen if they change address)");
				return DECODED.DECODED;
			}
		}

                // Don't log too much if we are a seednode
                if(logMINOR && crypto.isOpennet && wantAnonAuth) {
                	if(!didntTryOldOpennetPeers)
                		Logger.minor(this,"Unmatchable packet from "+peer);
                } else
                    Logger.normal(this,"Unmatchable packet from "+peer);
                
                if(!didntTryOldOpennetPeers)
                	return DECODED.NOT_DECODED;
                else
                	return DECODED.DIDNT_WANT_OPENNET;
	}
	
	private boolean checkAnonAuthChangeIP(PeerNode opn, byte[] buf, int offset, int length, Peer peer, long now) {
		PeerNode[] anonPeers = crypto.getAnonSetupPeerNodes();
		PeerNode pn;
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 3) {
			for(int i=0;i<anonPeers.length;i++) {
				pn = anonPeers[i];
				if(pn == opn) continue;
				if(tryProcessAuthAnonReply(buf, offset, length, pn, peer, now)) {
					return true;
				}
			}
		}
		if(length > HEADERS_LENGTH_MINIMUM) {
			for(int i=0;i<anonPeers.length;i++) {
				pn = anonPeers[i];
				if(pn == opn) continue;
				if(tryProcess(buf, offset, length, pn.getCurrentKeyTracker(), now)) {
					pn.changedIP(peer);
					return true;
				}
				if(tryProcess(buf, offset, length, pn.getPreviousKeyTracker(), now)) {
					pn.changedIP(peer);
					return true;
				}
				if(tryProcess(buf, offset, length, pn.getUnverifiedKeyTracker(), now)) {
					pn.changedIP(peer);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Is this a negotiation packet? If so, process it.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerNode pn, Peer peer, boolean oldOpennetPeer, long now) {
		BlockCipher authKey = pn.incomingSetupCipher;
		if(logDEBUG) Logger.debug(this, "Decrypt key: "+HexUtil.bytesToHex(pn.incomingSetupKey)+" for "+peer+" : "+pn+" in tryProcessAuth");
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 4) {
			if(logMINOR) {
				if(buf.length < length) {
					if(logDEBUG) Logger.debug(this, "The packet is smaller than the decrypted size: it's probably the wrong tracker ("+buf.length+'<'+length+')');
				} else {
					Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 4));
				}
			}
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
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
		if(logDEBUG) Logger.debug(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		MessageDigest md = SHA256.getMessageDigest();
		byte[] payload = new byte[dataLength];
		System.arraycopy(buf, dataStart, payload, 0, dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		md.update(payload);
		byte[] realHash = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuth(payload, pn, peer, oldOpennetPeer);
			pn.reportIncomingPacket(buf, offset, length, now);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be an anonymous-initiator negotiation packet (i.e.
	 * we are the responder).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param peer The Peer to send a reply to
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnon(byte[] buf, int offset, int length, Peer peer) {
		BlockCipher authKey = crypto.getAnonSetupCipher();
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		MessageDigest md = SHA256.getMessageDigest();
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logMINOR) Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			SHA256.returnMessageDigest(md);
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
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
			if(logMINOR) Logger.minor(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuthAnon");
			SHA256.returnMessageDigest(md);
			return false;
		}
		// Decrypt the data
		byte[] payload = new byte[dataLength];
		System.arraycopy(buf, dataStart, payload, 0, dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		md.update(payload);
		byte[] realHash = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnon(payload, peer);
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Incorrect hash in tryProcessAuthAnon for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be a reply to an anonymous-initiator negotiation
	 * packet (i.e. we are the initiator).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnonReply(byte[] buf, int offset, int length, PeerNode pn, Peer peer, long now) {
		BlockCipher authKey = pn.anonymousInitiatorSetupCipher;
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logDEBUG) Logger.debug(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
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
		if(logDEBUG) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		byte[] payload = new byte[dataLength];
		System.arraycopy(buf, dataStart, payload, 0, dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnonReply(payload, peer, pn);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	// Anonymous-initiator setup types
	/** Connect to a node hoping it will act as a seednode for us */
	static final byte SETUP_OPENNET_SEEDNODE = 1;

	/**
	 * Process an anonymous-initiator connection setup packet. For a normal setup
	 * (@see processDecryptedAuth()), we know the node that is trying to contact us.
	 * But in this case, we don't know the node yet, and we are doing a
	 * special-purpose connection setup. At the moment the only type supported is
	 * for a new node connecting to a seednode in order to announce. In future,
	 * nodes may support other anonymous-initiator connection types such as when a
	 * node (which is certain of its connectivity) issues one-time invites which
	 * allow a new node to connect to it.
	 * @param payload The decrypted payload of the packet.
	 * @param replyTo The address the packet came in from.
	 */
	private void processDecryptedAuthAnon(byte[] payload, Peer replyTo) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type. Common to anonymous-initiator auth and normal setup.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. This is specific to anonymous-initiator setup, and specifies the
		 * purpose of the connection. At the moment it is SETUP_OPENNET_SEEDNODE to indicate
		 * we are connecting to a seednode (which doesn't know us). Invites might require
		 * a different setupType. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 2 || negType == 4 || negType == 6 || negType == 7)) {
			Logger.error(this, "Unknown neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the RESPONDER.
		// Therefore, we can only get packets of phase 1 and 3 here.

		if(packetType == 0) {
			// Phase 1
			processJFKMessage1(payload,4,null,replyTo, true, setupType, negType);
		} else if(packetType == 2) {
			// Phase 3
			processJFKMessage3(payload, 4, null, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the responder) from "+replyTo);
		}
	}

	private void processDecryptedAuthAnonReply(byte[] payload, Peer replyTo, PeerNode pn) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. See above. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 2 || negType == 4 || negType == 6 || negType == 7)) {
			Logger.error(this, "Unknown neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the INITIATOR.
		// Therefore, we can only get packets of phase 2 and 4 here.

		if(packetType == 1) {
			// Phase 2
			processJFKMessage2(payload, 4, pn, replyTo, true, setupType, negType);
		} else if(packetType == 3) {
			// Phase 4
			processJFKMessage4(payload, 4, pn, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the initiator) from "+replyTo);
		}
	}

	/**
	 * Process a decrypted, authenticated auth packet.
	 * @param payload The packet payload, after it has been decrypted.
	 */
	private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo, boolean oldOpennetPeer) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn);
		if(pn.isDisabled()) {
			if(logMINOR) Logger.minor(this, "Won't connect to a disabled peer ("+pn+ ')');
			return;  // We don't connect to disabled peers
		}

		int negType = payload[1];
		int packetType = payload[2];
		int version = payload[0];

		if(logMINOR) {
			long now = System.currentTimeMillis();
			long last = pn.lastSentPacketTime();
			String delta = "never";
			if (last>0) {
				delta = TimeUtil.formatTime(now-last, 2, true)+" ago";
			}
			Logger.minor(this, "Received auth packet for "+pn.getPeer()+" (phase="+packetType+", v="+version+", nt="+negType+") (last packet sent "+delta+") from "+replyTo+"");
		}

		/* Format:
		 * 1 byte - version number (1)
		 * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi || 1 = StS)
		 * 1 byte - packet type (0-3)
		 */
		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}

		if(negType == 0) {
			Logger.error(this, "Old ephemeral Diffie-Hellman (negType 0) not supported.");
			return;
		} else if (negType == 1) {
			Logger.error(this, "Old StationToStation (negType 1) not supported.");
			return;
		} else if (negType==2 || negType == 4 || negType == 6 || negType == 7) {
			// negType == 3 was buggy
			// negType == 4 => negotiate whether to use a new PacketTracker when rekeying
			// negType == 5 => same as 4, but use new packet format after negotiation
			/*
			 * We implement Just Fast Keying key management protocol with active identity protection
			 * for the initiator and no identity protection for the responder
			 * M1:
			 * This is a straightforward DiffieHellman exponential.
			 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
			 * exponentials during different sessions while ensuring that the resulting session
			 * key will be different,can be used to differentiate between parallel sessions
			 * M2:
			 * Responder replies with a signed copy of his own exponential, a random nonce and
			 * an authenticator which provides sufficient defense against forgeries,replays
			 * We slightly deviate JFK here;we do not send any public key information as specified in the JFK docs
			 * M3:
			 * Initiator echoes the data sent by the responder including the authenticator.
			 * This helps the responder verify the authenticity of the returned data.
			 * M4:
			 * Encrypted message of the signature on both nonces, both exponentials using the same keys as in the previous message
			 */
			if(packetType<0 || packetType>3) {
				Logger.error(this,"Unknown PacketType" + packetType + "from" + replyTo + "from" +pn);
				return ;
			} else if(packetType==0) {
				/*
				 * Initiator- This is a straightforward DiffieHellman exponential.
				 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
				 * exponentials during different sessions while ensuring that the resulting
				 * session key will be different,can be used to differentiate between
				 * parallel sessions
				 */
				processJFKMessage1(payload,3,pn,replyTo,false,-1,negType);

			} else if(packetType==1) {
				/*
				 * Responder replies with a signed copy of his own exponential, a random
				 * nonce and an authenticator calculated from a transient hash key private
				 * to the responder.
				 */
				processJFKMessage2(payload,3,pn,replyTo,false,-1,negType);
			} else if(packetType==2) {
				/*
				 * Initiator echoes the data sent by the responder.These messages are
				 * cached by the Responder.Receiving a duplicate message simply causes
				 * the responder to Re-transmit the corresponding message4
				 */
				processJFKMessage3(payload, 3, pn, replyTo, oldOpennetPeer, false, -1, negType);
			} else if(packetType==3) {
				/*
				 * Encrypted message of the signature on both nonces, both exponentials
				 * using the same keys as in the previous message.
				 * The signature is non-message recovering
				 */
				processJFKMessage4(payload, 3, pn, replyTo, oldOpennetPeer, false, -1, negType);
			}
		} else {
			Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
			return;
		}
	}

	/*
	 * Initiator Method:Message1
	 * Process Message1
	 * Receive the Initiator nonce and DiffieHellman Exponential
	 * @param The packet phase number
	 * @param The peerNode we are talking to. CAN BE NULL if anonymous initiator, since we are the responder.
	 * @param The peer to which we need to send the packet
	 * @param unknownInitiator If true, we (the responder) don't know the
	 * initiator, and should check for fields which would be skipped in a
	 * normal setup where both sides know the other (indicated with * below).
	 * @param setupType The type of unknown-initiator setup.
	 *
	 * format :
	 * Ni
	 * g^i
	 * *IDr'
	 *
	 * See http://www.wisdom.weizmann.ac.il/~reingold/publications/jfk-tissec.pdf
	 * Just Fast Keying: Key Agreement In A Hostile Internet
	 * Aiello, Bellovin, Blaze, Canetti, Ioannidis, Keromytis, Reingold.
	 * ACM Transactions on Information and System Security, Vol 7 No 2, May 2004, Pages 1-30.
	 *
	 */
	private void processJFKMessage1(byte[] payload,int offset,PeerNode pn,Peer replyTo, boolean unknownInitiator, int setupType, int negType)
	{
		long t1=System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(1) message, processing it - "+pn);
		// FIXME: follow the spec and send IDr' ?
		if(payload.length < NONCE_SIZE + DiffieHellman.modulusLengthInBytes() + 3 + (unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0)) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(1), should be "+(NONCE_SIZE + DiffieHellman.modulusLengthInBytes()));
			return;
		}
		// get Ni
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, offset, nonceInitiator, 0, NONCE_SIZE);
		offset += NONCE_SIZE;

		// get g^i
		int modulusLength = DiffieHellman.modulusLengthInBytes();
		byte[] hisExponential = new byte[modulusLength];
		System.arraycopy(payload, offset, hisExponential, 0, modulusLength);
		if(unknownInitiator) {
			// Check IDr'
			offset += DiffieHellman.modulusLengthInBytes();
			byte[] expectedIdentityHash = new byte[NodeCrypto.IDENTITY_LENGTH];
			System.arraycopy(payload, offset, expectedIdentityHash, 0, expectedIdentityHash.length);
			if(!Arrays.equals(expectedIdentityHash, crypto.identityHash)) {
				Logger.error(this, "Invalid unknown-initiator JFK(1), IDr' is "+HexUtil.bytesToHex(expectedIdentityHash)+" should be "+HexUtil.bytesToHex(crypto.identityHash));
				return;
			}
		}
		
		if(throttleRekey(pn, replyTo)) return;

		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		if(DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
			sendJFKMessage2(nonceInitiator, hisExponential, pn, replyTo, unknownInitiator, setupType, negType);
		} else {
			Logger.error(this, "We can't accept the exponential "+pn+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
		}

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message1 timeout error:Processing packet for "+pn);
		}
	}
	
	private final LRUHashtable<InetAddress, Long> throttleRekeysByIP = new LRUHashtable<InetAddress, Long>();

	private static final int REKEY_BY_IP_TABLE_SIZE = 1024;

	private boolean throttleRekey(PeerNode pn, Peer replyTo) {
		if(pn != null) {
			return pn.throttleRekey();
		}
		long now = System.currentTimeMillis();
		InetAddress addr = replyTo.getAddress();
		synchronized(throttleRekeysByIP) {
			Long l = throttleRekeysByIP.get(addr);
			if(l == null || l != null && now > l)
				throttleRekeysByIP.push(addr, now);
			while(throttleRekeysByIP.size() > REKEY_BY_IP_TABLE_SIZE || 
					((!throttleRekeysByIP.isEmpty()) && throttleRekeysByIP.peekValue() < now - PeerNode.THROTTLE_REKEY))
				throttleRekeysByIP.popKey();
			if(l != null && now - l < PeerNode.THROTTLE_REKEY) {
				Logger.error(this, "Two JFK(1)'s initiated by same IP within "+PeerNode.THROTTLE_REKEY+"ms");
				return true;
			}
		}
		return false;
	}

	private static final int MAX_NONCES_PER_PEER = 10;

	/*
	 * format:
	 * Ni,g^i
	 * We send IDr' only if unknownInitiator is set.
	 * @param pn The node to encrypt the message to. Cannot be null, because we are the initiator and we
	 * know the responder in all cases.
	 * @param replyTo The peer to send the actual packet to.
	 */
	private void sendJFKMessage1(PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, int negType) {
		if(logMINOR) Logger.minor(this, "Sending a JFK(1) message to "+replyTo+" for "+pn.getPeer());
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext ctx = (DiffieHellmanLightContext) pn.getKeyAgreementSchemeContext();
		if((ctx == null) || ((pn.jfkContextLifetime + DH_GENERATION_INTERVAL*DH_CONTEXT_BUFFER_SIZE) < now)) {
			pn.jfkContextLifetime = now;
			pn.setKeyAgreementSchemeContext(ctx = getLightDiffieHellmanContext());
		}
		int offset = 0;
		byte[] myExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		byte[] nonce = new byte[NONCE_SIZE];
		node.random.nextBytes(nonce);

		synchronized (pn) {
			pn.jfkNoncesSent.add(nonce);
			if(pn.jfkNoncesSent.size() > MAX_NONCES_PER_PEER)
				pn.jfkNoncesSent.removeFirst();
		}

		int modulusLength = DiffieHellman.modulusLengthInBytes();
		byte[] message1 = new byte[NONCE_SIZE+modulusLength+(unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0)];

		System.arraycopy(nonce, 0, message1, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message1, offset, modulusLength);

		if(unknownInitiator) {
			offset += modulusLength;
			System.arraycopy(pn.identityHash, 0, message1, offset, pn.identityHash.length);
			sendAnonAuthPacket(1,negType,0,setupType,message1,pn,replyTo,pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1,negType,0,message1,pn,replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-now)>500) {
			Logger.error(this,"Message1 timeout error:Sending packet for "+pn.getPeer());
		}
	}

	/*
	 * format:
	 * Ni,Nr,g^r
	 * Signature[g^r,grpInfo(r)] - R, S
	 * Hashed JFKAuthenticator : HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 *
	 * NB: we don't send IDr nor groupinfo as we know them: even if the responder doesn't know the initiator,
	 * the initiator ALWAYS knows the responder.
	 * @param pn The node to encrypt the message for. CAN BE NULL if anonymous-initiator.
	 * @param replyTo The peer to send the packet to.
	 */
	private void sendJFKMessage2(byte[] nonceInitator, byte[] hisExponential, PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, int negType) {
		if(logMINOR) Logger.minor(this, "Sending a JFK(2) message to "+pn);
		DiffieHellmanLightContext ctx = getLightDiffieHellmanContext();
		// g^r
		byte[] myExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		// Nr
		byte[] myNonce = new byte[NONCE_SIZE];
		node.random.nextBytes(myNonce);
		byte[] r = ctx.signature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = ctx.signature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] authenticator = HMAC.macWithSHA256(getTransientKey(),assembleJFKAuthenticator(myExponential, hisExponential, myNonce, nonceInitator, replyTo.getAddress().getAddress()), HASH_LENGTH);
		if(logMINOR) Logger.minor(this, "We are using the following HMAC : " + HexUtil.bytesToHex(authenticator));

		byte[] message2 = new byte[NONCE_SIZE*2+DiffieHellman.modulusLengthInBytes()+
		                           Node.SIGNATURE_PARAMETER_LENGTH*2+
		                           HASH_LENGTH];

		int offset = 0;
		System.arraycopy(nonceInitator, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myNonce, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message2, offset, DiffieHellman.modulusLengthInBytes());
		offset += DiffieHellman.modulusLengthInBytes();

		System.arraycopy(r, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;

		System.arraycopy(authenticator, 0, message2, offset, HASH_LENGTH);

		if(unknownInitiator) {
			sendAnonAuthPacket(1,negType,1,setupType,message2,pn,replyTo,crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1,negType,1,message2,pn,replyTo);
		}
	}

	/*
	 * Assemble what will be the jfk-Authenticator :
	 * computed over the Responder exponentials and the Nonces and
	 * used by the responder to verify that the round-trip has been done
	 *
	 */
	private byte[] assembleJFKAuthenticator(byte[] gR, byte[] gI, byte[] nR, byte[] nI, byte[] address) {
		byte[] authData=new byte[gR.length + gI.length + nR.length + nI.length + address.length];
		int offset = 0;

		System.arraycopy(gR, 0, authData, offset ,gR.length);
		offset += gR.length;
		System.arraycopy(gI, 0, authData, offset, gI.length);
		offset += gI.length;
		System.arraycopy(nR, 0,authData, offset, nR.length);
		offset += nR.length;
		System.arraycopy(nI, 0,authData, offset, nI.length);
		offset += nI.length;
		System.arraycopy(address, 0, authData, offset, address.length);

		return authData;
	}

	/*
	 * Initiator Method:Message2
	 * @see{sendJFKMessage2} for packet format details.
	 * Note that this packet is exactly the same for known initiator as for unknown initiator.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param inputOffset The offset in the buffer at which the packet starts.
	 * @param replyTo The peer to which we need to send the packet
	 * @param pn The peerNode we are talking to. Cannot be null as we are the initiator.
	 */

	private void processJFKMessage2(byte[] payload,int inputOffset,PeerNode pn,Peer replyTo, boolean unknownInitiator, int setupType, int negType)
	{
		long t1=System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(2) message, processing it - "+pn.getPeer());
		// FIXME: follow the spec and send IDr' ?
		int expectedLength = NONCE_SIZE*2 + DiffieHellman.modulusLengthInBytes() + HASH_LENGTH*2;
		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn.getPeer()+": "+payload.length+" after decryption in JFK(2), should be "+(expectedLength + 3));
			return;
		}

		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceInitiator, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		byte[] nonceResponder = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceResponder, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;

		byte[] hisExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, hisExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();
		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);

		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(payload, inputOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(payload, inputOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;

		byte[] authenticator = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, authenticator, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message2
		// Now simply transmit the corresponding message3
		Object message3 = null;
		synchronized (authenticatorCache) {
			message3 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message3 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+pn.getPeer());
			sendAuthPacket(1, negType, 3, (byte[]) message3, pn, replyTo);
			return;
		}

		// sanity check
		byte[] myNi = null;
		synchronized (pn) {
			for(byte[] buf : pn.jfkNoncesSent) {
				if(Arrays.equals(nonceInitiator, buf))
					myNi = buf;
			}
		}
		// We don't except such a message;
		if(myNi == null) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "We received an unexpected JFK(2) message from "+pn.getPeer()+" (time since added: "+pn.timeSinceAddedOrRestarted()+" time last receive:"+pn.lastReceivedPacketTime()+')');
			}
			return;
		} else if(!Arrays.equals(myNi, nonceInitiator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "Ignoring old JFK(2) (different nonce to the one we sent - either a timing artefact or an attempt to change the nonce)");
			}
			return;
		}

		if(!DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
			Logger.error(this, "We can't accept the exponential "+pn.getPeer()+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
			return;
		}

		// Verify the DSA signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		// At that point we don't know if it's "him"; let's check it out
		byte[] locallyExpectedExponentials = assembleDHParams(_hisExponential, pn.peerCryptoGroup);

		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(locallyExpectedExponentials)), false)) {
			Logger.error(this, "The signature verification has failed in JFK(2)!! "+pn.getPeer());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true, false);

		sendJFKMessage3(1, negType, 3, nonceInitiator, nonceResponder, hisExponential, authenticator, pn, replyTo, unknownInitiator, setupType);

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message2 timeout error:Processing packet for "+pn.getPeer());
		}
	}

	/*
	 * Initiator Method:Message3
	 * Process Message3
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * and initiator in the clear.(unVerifiedData)
	 * Send the authenticator which allows the responder to verify that a roundtrip occured
	 * Compute the signature of the unVerifiedData and encrypt it using a shared key
	 * which is derived from DHExponentials and the nonces; add a HMAC to protect it
	 *
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI*]
	 *
	 * * Noderef is sent whether or not unknownInitiator is true, however if it is, it will
	 * be a *full* noderef, otherwise it will exclude the pubkey etc.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param replyTo The peer to which we need to send the packet.
	 * @param pn The PeerNode we are talking to. CAN BE NULL in the case of anonymous initiator since we are the
	 * responder.
	 * @return byte Message3
	 */
	private void processJFKMessage3(byte[] payload, int inputOffset, PeerNode pn,Peer replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		final long t1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(3) message, processing it - "+pn);

		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		final int expectedLength =
			NONCE_SIZE*2 + // Ni, Nr
			DiffieHellman.modulusLengthInBytes()*2 + // g^i, g^r
			HASH_LENGTH + // authenticator
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			HASH_LENGTH + // it's at least a signature
			8 +	      // a bootid
			(negType >= 4 ? 8 : 0) + // packet tracker ID
			1;	      // znoderefI* is at least 1 byte long

		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(3), should be "+(expectedLength + 3));
			return;
		}

		// Ni
		byte[] nonceInitiator = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceInitiator, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// Nr
		byte[] nonceResponder = new byte[NONCE_SIZE];
		System.arraycopy(payload, inputOffset, nonceResponder, 0, NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// g^i
		byte[] initiatorExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, initiatorExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();
		// g^r
		byte[] responderExponential = new byte[DiffieHellman.modulusLengthInBytes()];
		System.arraycopy(payload, inputOffset, responderExponential, 0, DiffieHellman.modulusLengthInBytes());
		inputOffset += DiffieHellman.modulusLengthInBytes();

		byte[] authenticator = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, authenticator, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// We *WANT* to check the hmac before we do the lookup on the hashmap
		// @see https://bugs.freenetproject.org/view.php?id=1604
		if(!HMAC.verifyWithSHA256(getTransientKey(), assembleJFKAuthenticator(responderExponential, initiatorExponential, nonceResponder, nonceInitiator, replyTo.getAddress().getAddress()) , authenticator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "The HMAC doesn't match; let's discard the packet (either we rekeyed or we are victim of forgery) - JFK3 - "+pn);
			}
			return;
		}
		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message3
		// Now simply transmit the corresponding message4
		Object message4 = null;
		synchronized (authenticatorCache) {
			message4 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message4 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+pn);
			// We are replaying a JFK(4).
			// Therefore if it is anon-initiator it is encrypted with our setup key.
			if(unknownInitiator) {
				sendAnonAuthPacket(1,negType,3,setupType, (byte[]) message4, null, replyTo, crypto.anonSetupCipher);
			} else {
				sendAuthPacket(1, negType, 3, (byte[]) message4, pn, replyTo);
			}
			return;
		} else {
			if(logDEBUG) Logger.debug(this, "No message4 found for "+HexUtil.bytesToHex(authenticator)+" responderExponential "+Fields.hashCode(responderExponential)+" initiatorExponential "+Fields.hashCode(initiatorExponential)+" nonceResponder "+Fields.hashCode(nonceResponder)+" nonceInitiator "+Fields.hashCode(nonceInitiator)+" address "+HexUtil.bytesToHex(replyTo.getAddress().getAddress()));
		}

		NativeBigInteger _hisExponential = new NativeBigInteger(1, initiatorExponential);
		NativeBigInteger _ourExponential = new NativeBigInteger(1, responderExponential);

		byte[] hmac = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, hmac, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		DiffieHellmanLightContext ctx = findContextByExponential(_ourExponential);
		if(ctx == null) {
			Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN! - JFK3 - "+pn);
			return;
		}
		BigInteger computedExponential = ctx.getHMACKey(_hisExponential, Global.DHgroupA);

		/* 0 is the outgoing key for the initiator, 7 for the responder */
		byte[] outgoingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "7");
		byte[] incommingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		byte[] Ke = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		byte[] Ka = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");

		byte[] hmacKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "3");
		byte[] ivKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "4");
		byte[] ivNonce = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "5");

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the responder */
		byte[] sharedData = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "6");
		int theirInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		int ourInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		int theirInitialMsgID, ourInitialMsgID;
		if(negType >= 7) {
			theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
			ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			theirInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			ourInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+theirInitialMsgID+" ours "+ourInitialMsgID);

		if(negType <= 4) {
			/* Negtypes <= 4 were deployed when the keys were split, so use the initiator key to be
			 * backwards compatible */
			outgoingKey = incommingKey;
		}

		c.initialize(Ke);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("I"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = new byte[JFK_PREFIX_INITIATOR.length + payload.length - inputOffset];
		System.arraycopy(JFK_PREFIX_INITIATOR, 0, decypheredPayload, decypheredPayloadOffset, JFK_PREFIX_INITIATOR.length);
		decypheredPayloadOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		if(!HMAC.verifyWithSHA256(Ka, decypheredPayload, hmac)) {
			Logger.error(this, "The inner-HMAC doesn't match; let's discard the packet JFK(3) - "+pn);
			return;
		}

		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		// Get the IV
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * Node Data (starting with BootID)
		 */
		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = new byte[decypheredPayload.length - decypheredPayloadOffset];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, data, 0, decypheredPayload.length - decypheredPayloadOffset);
		int ptr = 0;
		long trackerID;
		if(negType >= 4) {
			trackerID = Fields.bytesToLong(data, ptr);
			if(trackerID < 0) trackerID = -1;
			ptr += 8;
		} else {
			trackerID = -1;
		}
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = new byte[data.length - ptr];
		System.arraycopy(data, ptr, hisRef, 0, hisRef.length);

		// construct the peernode
		if(unknownInitiator) {
			pn = getPeerNodeFromUnknownInitiator(hisRef, setupType, pn, replyTo);
		}
		if(pn == null) {
			if(unknownInitiator) {
				// Reject
				Logger.normal(this, "Rejecting... unable to construct PeerNode");
			} else {
				Logger.error(this, "PeerNode is null and unknownInitiator is false!");
			}
			return;
		}

		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(assembleDHParams(nonceInitiator, nonceResponder, _hisExponential, _ourExponential, crypto.myIdentity, data))), false)) {
			Logger.error(this, "The signature verification has failed!! JFK(3) - "+pn.getPeer());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true, false);

		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		BlockCipher ivCipher = null;
		try {
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
			ivCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}
		outgoingCipher.initialize(outgoingKey);
		incommingCipher.initialize(incommingKey);
		ivCipher.initialize(ivKey);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		if((!dontWant) && !crypto.allowConnection(pn, replyTo.getFreenetAddress())) {
			if(pn instanceof DarknetPeerNode) {
				Logger.error(this, "Dropping peer "+pn+" because don't want connection due to others on the same IP address!");
				System.out.println("Disconnecting permanently from your friend \""+((DarknetPeerNode)pn).getName()+"\" because other peers are using the same IP address!");
			}
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, outgoingKey, incommingCipher,
				incommingKey, replyTo, true, negType, trackerID, false, false, hmacKey, ivCipher,
				ivNonce, ourInitialSeqNum, theirInitialSeqNum, ourInitialMsgID, theirInitialMsgID);

		if(newTrackerID > 0) {

			// Send reply
			sendJFKMessage4(1, negType, 3, nonceInitiator, nonceResponder,initiatorExponential, responderExponential,
					c, Ke, Ka, authenticator, hisRef, pn, replyTo, unknownInitiator, setupType, newTrackerID, newTrackerID == trackerID);

			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true); // Let it connect then tell it to remove it.
			} else {
				pn.maybeSendInitialMessages();
			}
		} else {
			Logger.error(this, "Handshake failure! with "+pn.getPeer());
			// Don't send the JFK(4). We have not successfully connected.
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message3 Processing packet for "+pn.getPeer()+" took "+TimeUtil.formatTime(t2-t1, 3, true));
		}
	}

	private PeerNode getPeerNodeFromUnknownInitiator(byte[] hisRef, int setupType, PeerNode pn, Peer from) {
		if(setupType == SETUP_OPENNET_SEEDNODE) {
			OpennetManager om = node.getOpennet();
			if(om == null) {
				Logger.error(this, "Opennet disabled, ignoring seednode connect attempt");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			SimpleFieldSet ref = OpennetManager.validateNoderef(hisRef, 0, hisRef.length, null, true);
			if(ref == null) {
				Logger.error(this, "Invalid noderef");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			PeerNode seed;
			try {
				seed = new SeedClientPeerNode(ref, node, crypto, node.peers, false, true, crypto.packetMangler);
				// Don't tell tracker yet as we don't have the address yet.
			} catch (FSParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (PeerParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (ReferenceSignatureVerificationException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			}
			if(seed.equals(pn)) {
				Logger.normal(this, "Already connected to seednode");
				return pn;
			}
			node.peers.addPeer(seed);
			return seed;
		} else {
			Logger.error(this, "Unknown setup type");
			return null;
		}
	}

	/*
	 * Responder Method:Message4
	 * Process Message4
	 *
	 * Format:
	 * HMAC{Ka}[cyphertext]
	 * IV + E{Ke}[S{R}[Ni, Nr, g^i, g^r, IDi, bootID, znoderefR, znoderefI], bootID, znoderefR]
	 *
	 * @param payload The decrypted auth packet.
	 * @param pn The PeerNode we are talking to. Cannot be null as we are the initiator.
	 * @param replyTo The Peer we are replying to.
	 */
	private boolean processJFKMessage4(byte[] payload, int inputOffset, PeerNode pn, Peer replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		final long t1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(4) message, processing it - "+pn.getPeer());
		if(pn.jfkMyRef == null) {
			String error = "Got a JFK(4) message but no pn.jfkMyRef for "+pn;
			if(node.getUptime() < 60*1000) {
				Logger.minor(this, error);
			} else {
				Logger.error(this, error);
			}
		}
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		final int expectedLength =
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			Node.SIGNATURE_PARAMETER_LENGTH * 2 + // the signature
			(negType >= 4 ? 9 : 0) + // ID of packet tracker, plus boolean byte
			8+ // bootID
			1; // znoderefR

		if(payload.length - inputOffset < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+pn.getPeer()+": "+payload.length+" after decryption in JFK(4), should be "+(expectedLength + 3));
			return false;
		}
		byte[] jfkBuffer = pn.getJFKBuffer();
		if(jfkBuffer == null) {
			Logger.normal(this, "We have already handled this message... might be a replay or a bug - "+pn);
			return false;
		}

		byte[] hmac = new byte[HASH_LENGTH];
		System.arraycopy(payload, inputOffset, hmac, 0, HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		c.initialize(pn.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("R"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = new byte[JFK_PREFIX_RESPONDER.length + (payload.length-inputOffset)];
		System.arraycopy(JFK_PREFIX_RESPONDER, 0, decypheredPayload, decypheredPayloadOffset, JFK_PREFIX_RESPONDER.length);
		decypheredPayloadOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, payload.length-inputOffset);
		if(!HMAC.verifyWithSHA256(pn.jfkKa, decypheredPayload, hmac)) {
			Logger.normal(this, "The digest-HMAC doesn't match; let's discard the packet - "+pn.getPeer());
			return false;
		}

		// Try to find the HMAC in the cache:
		// If it is already present it indicates duplicate/replayed message4 and we can discard
		// If it's not, we can add it with a timestamp
		byte[] message4Timestamp = null;
		synchronized (authenticatorCache) {
			ByteArrayWrapper hmacBAW = new ByteArrayWrapper(hmac);
			message4Timestamp = authenticatorCache.get(hmacBAW);
			if(message4Timestamp == null) { // normal behaviour
				authenticatorCache.put(hmacBAW, Fields.longToBytes(t1));
			}
		}
		if(message4Timestamp != null) {
			Logger.normal(this, "We got a replayed message4 (first handled at "+TimeUtil.formatTime(t1-Fields.bytesToLong(message4Timestamp))+") from - "+pn);
			return true;
		}

		// Get the IV
		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length - decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * bootID, znoderef
		 */
		byte[] r = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, r, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = new byte[Node.SIGNATURE_PARAMETER_LENGTH];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, s, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = new byte[decypheredPayload.length - decypheredPayloadOffset];
		System.arraycopy(decypheredPayload, decypheredPayloadOffset, data, 0, decypheredPayload.length - decypheredPayloadOffset);
		int ptr = 0;
		long trackerID;
		boolean reusedTracker;
		if(negType >= 4) {
			trackerID = Fields.bytesToLong(data, ptr);
			ptr += 8;
			reusedTracker = data[ptr++] != 0;
		} else {
			trackerID = -1;
			reusedTracker = false;
		}
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = new byte[data.length - ptr];
		System.arraycopy(data, ptr, hisRef, 0, hisRef.length);

		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		int dataLen = hisRef.length + 8 + (negType >= 4 ? 9 : 0);
		byte[] locallyGeneratedText = new byte[NONCE_SIZE * 2 + DiffieHellman.modulusLengthInBytes() * 2 + crypto.myIdentity.length + dataLen + pn.jfkMyRef.length];
		int bufferOffset = NONCE_SIZE * 2 + DiffieHellman.modulusLengthInBytes()*2;
		System.arraycopy(jfkBuffer, 0, locallyGeneratedText, 0, bufferOffset);
		byte[] identity = crypto.getIdentity(unknownInitiator);
		System.arraycopy(identity, 0, locallyGeneratedText, bufferOffset, identity.length);
		bufferOffset += identity.length;
		// bootID
		System.arraycopy(data, 0, locallyGeneratedText, bufferOffset, dataLen);
		bufferOffset += dataLen;
		System.arraycopy(pn.jfkMyRef, 0, locallyGeneratedText, bufferOffset, pn.jfkMyRef.length);
		byte[] messageHash = SHA256.digest(locallyGeneratedText);
		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, messageHash), false)) {
			String error = "The signature verification has failed!! JFK(4) -"+pn.getPeer()+" message hash "+HexUtil.bytesToHex(messageHash)+" length "+locallyGeneratedText.length+" hisRef "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" myRef "+pn.jfkMyRef.length+" hash "+Fields.hashCode(pn.jfkMyRef)+" boot ID "+bootID;
			Logger.error(this, error);
			return true;
		}

		// Received a packet
		pn.receivedPacket(true, false);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return true;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		if((!dontWant) && !crypto.allowConnection(pn, replyTo.getFreenetAddress())) {
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		// We change the key
		BlockCipher ivCipher = null;
		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		try {
			ivCipher = new Rijndael(256, 256);
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}

		outgoingCipher.initialize(pn.outgoingKey);
		incommingCipher.initialize(pn.incommingKey);
		ivCipher.initialize(pn.ivKey);

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, pn.outgoingKey, incommingCipher,
				pn.incommingKey, replyTo, false, negType, trackerID, true, reusedTracker, pn.hmacKey,
				ivCipher, pn.ivNonce, pn.ourInitialSeqNum, pn.theirInitialSeqNum, pn.ourInitialMsgID,
				pn.theirInitialMsgID);
		if(newTrackerID >= 0) {
			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true);
			} else {
				pn.maybeSendInitialMessages();
			}
		} else {
			Logger.error(this, "Handshake failed!");
		}

		// cleanup
		// FIXME: maybe we should copy zeros/garbage into it before leaving it to the GC
		pn.setJFKBuffer(null);
		pn.jfkKa = null;
		pn.jfkKe = null;
		pn.outgoingKey = null;
		pn.incommingKey = null;
		pn.hmacKey = null;
		pn.ivKey = null;
		pn.ivNonce = null;
		pn.ourInitialSeqNum = 0;
		pn.theirInitialSeqNum = 0;
		pn.ourInitialMsgID = 0;
		pn.theirInitialMsgID = 0;
		// We want to clear it here so that new handshake requests
		// will be sent with a different DH pair
		pn.setKeyAgreementSchemeContext(null);
		synchronized (pn) {
			// FIXME TRUE MULTI-HOMING: winner-takes-all, kill all other connection attempts since we can't deal with multiple active connections
			// Also avoids leaking
			pn.jfkNoncesSent.clear();
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Processing packet from "+pn.getPeer());
		return true;
	}

	/*
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI]
	 *
	 * @param pn The PeerNode to encrypt the message for. Cannot be null as we are the initiator.
	 * @param replyTo The Peer to send the packet to.
	 */

	private void sendJFKMessage3(int version,final int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] hisExponential, byte[] authenticator, final PeerNode pn, final Peer replyTo, final boolean unknownInitiator, final int setupType)
	{
		if(logMINOR) Logger.minor(this, "Sending a JFK(3) message to "+pn.getPeer());
		long t1=System.currentTimeMillis();
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }
		DiffieHellmanLightContext ctx = (DiffieHellmanLightContext) pn.getKeyAgreementSchemeContext();
		if(ctx == null) return;
		byte[] ourExponential = stripBigIntegerToNetworkFormat(ctx.myExponential);
		pn.jfkMyRef = unknownInitiator ? crypto.myCompressedHeavySetupRef() : crypto.myCompressedSetupRef();
		byte[] data = new byte[(negType >= 4 ? 8 : 0) + 8 + pn.jfkMyRef.length];
		int ptr = 0;
		if(negType >= 4) {
			long trackerID;
			trackerID = pn.getReusableTrackerID();
			System.arraycopy(Fields.longToBytes(trackerID), 0, data, ptr, 8);
			ptr += 8;
			if(logMINOR) Logger.minor(this, "Sending tracker ID "+trackerID+" in JFK(3)");
		}
		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(pn.jfkMyRef, 0, data, ptr, pn.jfkMyRef.length);
		final byte[] message3 = new byte[NONCE_SIZE*2 + // nI, nR
		                           DiffieHellman.modulusLengthInBytes()*2 + // g^i, g^r
		                           HASH_LENGTH + // authenticator
		                           HASH_LENGTH + // HMAC(cyphertext)
		                           (c.getBlockSize() >> 3) + // IV
		                           Node.SIGNATURE_PARAMETER_LENGTH * 2 + // Signature (R,S)
		                           data.length]; // The bootid+noderef
		int offset = 0;
		// Ni
		System.arraycopy(nonceInitiator, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// Nr
		System.arraycopy(nonceResponder, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// g^i
		System.arraycopy(ourExponential, 0,message3, offset, ourExponential.length);
		offset += ourExponential.length;
		// g^r
		System.arraycopy(hisExponential, 0,message3, offset, hisExponential.length);
		offset += hisExponential.length;

		// Authenticator
		System.arraycopy(authenticator, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		/*
		 * Digital Signature of the message with the private key belonging to the initiator/responder
		 * It is assumed to be non-message recovering
		 */
		NativeBigInteger _ourExponential = new NativeBigInteger(1,ourExponential);
		NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		// save parameters so that we can verify message4
		byte[] toSign = assembleDHParams(nonceInitiator, nonceResponder, _ourExponential, _hisExponential, pn.identity, data);
		pn.setJFKBuffer(toSign);
		DSASignature localSignature = crypto.sign(SHA256.digest(toSign));
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);

		BigInteger computedExponential = ctx.getHMACKey(_hisExponential, Global.DHgroupA);

		/* 0 is the outgoing key for the initiator, 7 for the responder */
		pn.outgoingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		pn.incommingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "7");
		pn.jfkKe = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		pn.jfkKa = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");

		pn.hmacKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "3");
		pn.ivKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "4");
		pn.ivNonce = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "5");

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the initiator */
		byte[] sharedData = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "6");
		pn.ourInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		pn.theirInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		if(negType >= 7) {
			pn.theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
			pn.ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			pn.ourInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			pn.theirInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
			
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+pn.theirInitialMsgID+" ours "+pn.ourInitialMsgID);


		if(negType <= 4) {
			/* Negtypes <= 4 were deployed when the keys were split, so use the initiator key to be
			 * backwards compatible */
			pn.incommingKey = pn.outgoingKey;
		}

		c.initialize(pn.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv = new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pcfb = PCFBMode.create(c, iv);
		int cleartextOffset = 0;
		byte[] cleartext = new byte[JFK_PREFIX_INITIATOR.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 + data.length];
		System.arraycopy(JFK_PREFIX_INITIATOR, 0, cleartext, cleartextOffset, JFK_PREFIX_INITIATOR.length);
		cleartextOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(iv, 0, cleartext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cleartext, cleartextOffset, data.length);
		cleartextOffset += data.length;

		int cleartextToEncypherOffset = JFK_PREFIX_INITIATOR.length + ivLength;
		pcfb.blockEncipher(cleartext, cleartextToEncypherOffset, cleartext.length-cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + cyphertext) Includes the IV!
		byte[] hmac = HMAC.macWithSHA256(pn.jfkKa, cleartext, HASH_LENGTH);

		// copy stuffs back to the message
		System.arraycopy(hmac, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message3, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cleartext, cleartextToEncypherOffset, message3, offset, cleartext.length-cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator),message3);
		}
		final long timeSent = System.currentTimeMillis();
		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 2, setupType, message3, pn, replyTo, pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1, negType, 2, message3, pn, replyTo);
		}

		/* Re-send the packet after 5sec if we don't get any reply */
		node.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				if(pn.timeLastConnectionCompleted() < timeSent) {
					if(logMINOR) Logger.minor(this, "Resending JFK(3) to "+pn+" for "+node.getDarknetPortNumber());
					if(unknownInitiator) {
						sendAnonAuthPacket(1, negType, 2, setupType, message3, pn, replyTo, pn.anonymousInitiatorSetupCipher);
					} else {
						sendAuthPacket(1, negType, 2, message3, pn, replyTo);
					}
				}
			}
		}, 5*1000);
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message3 timeout error:Sending packet for "+pn.getPeer());
	}

	private int getInitialMessageID(byte[] identity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL0".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	private int getInitialMessageID(byte[] identity, byte[] otherIdentity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		md.update(otherIdentity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL1".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	/*
	 * Format:
	 * HMAC{Ka}(cyphertext)
	 * IV, E{Ke}[S{R}[Ni,Nr,g^i,g^r,idI, bootID, znoderefR, znoderefI],bootID,znoderefR]
	 *
	 * @param replyTo The Peer we are replying to.
	 * @param pn The PeerNode to encrypt the auth packet to. Cannot be null, because even in anonymous initiator,
	 * we will have created one before calling this method.
	 */
	private void sendJFKMessage4(int version,int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] initiatorExponential,byte[] responderExponential, BlockCipher c, byte[] Ke, byte[] Ka, byte[] authenticator, byte[] hisRef, PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, long newTrackerID, boolean sameAsOldTrackerID)
	{
		if(logMINOR)
			Logger.minor(this, "Sending a JFK(4) message to "+pn.getPeer());
		long t1=System.currentTimeMillis();
		NativeBigInteger _responderExponential = new NativeBigInteger(1,responderExponential);
		NativeBigInteger _initiatorExponential = new NativeBigInteger(1,initiatorExponential);

		byte[] myRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[(negType >= 4 ? 9 : 0) + 8 + myRef.length + hisRef.length];
		int ptr = 0;
		if(negType >= 4) {
			System.arraycopy(Fields.longToBytes(newTrackerID), 0, data, ptr, 8);
			ptr += 8;
			data[ptr++] = (byte) (sameAsOldTrackerID ? 1 : 0);
		}

		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(myRef, 0, data, ptr, myRef.length);
		ptr += myRef.length;
		System.arraycopy(hisRef, 0, data, ptr, hisRef.length);

		byte[] params = assembleDHParams(nonceInitiator, nonceResponder, _initiatorExponential, _responderExponential, pn.identity, data);
		byte[] messageHash = SHA256.digest(params);
		if(logMINOR)
			Logger.minor(this, "Message hash: "+HexUtil.bytesToHex(messageHash)+" length "+params.length+" myRef: "+myRef.length+" hash "+Fields.hashCode(myRef)+" hisRef: "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" boot ID "+node.bootID);
		DSASignature localSignature = crypto.sign(messageHash);
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);

		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv=new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pk=PCFBMode.create(c, iv);
		// Don't include the last bit
		int dataLength = data.length - hisRef.length;
		byte[] cyphertext = new byte[JFK_PREFIX_RESPONDER.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 +
		                             dataLength];
		int cleartextOffset = 0;
		System.arraycopy(JFK_PREFIX_RESPONDER, 0, cyphertext, cleartextOffset, JFK_PREFIX_RESPONDER.length);
		cleartextOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(iv, 0, cyphertext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cyphertext, cleartextOffset, dataLength);
		cleartextOffset += dataLength;
		// Now encrypt the cleartext[Signature]
		int cleartextToEncypherOffset = JFK_PREFIX_RESPONDER.length + ivLength;
		pk.blockEncipher(cyphertext, cleartextToEncypherOffset, cyphertext.length - cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + iv + signature)
		byte[] hmac = HMAC.macWithSHA256(Ka, cyphertext, HASH_LENGTH);

		// Message4 = hmac + IV + encryptedSignature
		byte[] message4 = new byte[HASH_LENGTH + ivLength + (cyphertext.length - cleartextToEncypherOffset)];
		int offset = 0;
		System.arraycopy(hmac, 0, message4, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message4, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cyphertext, cleartextToEncypherOffset, message4, offset, cyphertext.length - cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator), message4);
			if(logDEBUG) Logger.debug(this, "Storing JFK(4) for "+HexUtil.bytesToHex(authenticator));
		}

		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 3, setupType, message4, pn, replyTo, crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1, negType, 3, message4, pn, replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Sending packet for "+pn.getPeer());
	}

	/**
	 * Send an auth packet.
	 */
	private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerNode pn, Peer replyTo) {
		if(pn == null) throw new IllegalArgumentException("pn shouldn't be null here!");
		byte[] output = new byte[data.length+3];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) {
			long now = System.currentTimeMillis();
			String delta = "never";
			long last = pn.lastSentPacketTime();
			delta = TimeUtil.formatTime(now - last, 2, true) + " ago";
			Logger.minor(this, "Sending auth packet for "+ String.valueOf(pn.getPeer())+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+delta+") to "+replyTo+" data.length="+data.length+" to "+replyTo);
		}
		sendAuthPacket(output, pn.outgoingSetupCipher, pn, replyTo, false);
	}

	/**
	 * @param version
	 * @param negType
	 * @param phase
	 * @param setupType
	 * @param data
	 * @param pn May be null. If not null, used for details such as anti-firewall hacks.
	 * @param replyTo
	 * @param cipher
	 */
	private void sendAnonAuthPacket(int version, int negType, int phase, int setupType, byte[] data, PeerNode pn, Peer replyTo, BlockCipher cipher) {
		byte[] output = new byte[data.length+4];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		output[3] = (byte) setupType;
		System.arraycopy(data, 0, output, 4, data.length);
		if(logMINOR) Logger.minor(this, "Sending anon auth packet (phase="+phase+", ver="+version+", nt="+negType+", setup="+setupType+") data.length="+data.length);
		sendAuthPacket(output, cipher, pn, replyTo, true);
	}

	/**
	 * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
	 */
	private void sendAuthPacket(byte[] output, BlockCipher cipher, PeerNode pn, Peer replyTo, boolean anonAuth) {
		int length = output.length;
		if(length > sock.getMaxPacketSize()) {
			throw new IllegalStateException("Cannot send auth packet: too long: "+length);
		}
		byte[] iv = new byte[PCFBMode.lengthIV(cipher)];
		node.random.nextBytes(iv);
		byte[] hash = SHA256.digest(output);
		if(logMINOR) Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
		int prePaddingLength = iv.length + hash.length + 2 /* length */ + output.length;
		int maxPacketSize = sock.getMaxPacketSize() - sock.getHeadersLength();
		int paddingLength;
		if(prePaddingLength < maxPacketSize) {
			paddingLength = node.fastWeakRandom.nextInt(Math.min(100, maxPacketSize - prePaddingLength));
		} else {
			paddingLength = 0; // Avoid oversize packets if at all possible, the MTU is an estimate and may be wrong, and fragmented packets are often dropped by firewalls.
			// Tell the devs, this shouldn't happen.
			Logger.error(this, "Warning: sending oversize auth packet (anonAuth="+anonAuth+") of "+prePaddingLength+" bytes!");
		}
		if(paddingLength < 0) paddingLength = 0;
		byte[] data = new byte[prePaddingLength + paddingLength];
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		System.arraycopy(iv, 0, data, 0, iv.length);
		pcfb.blockEncipher(hash, 0, hash.length);
		System.arraycopy(hash, 0, data, iv.length, hash.length);
		if(logMINOR) Logger.minor(this, "Payload length: "+length);
		data[hash.length+iv.length] = (byte) pcfb.encipher((byte)(length>>8));
		data[hash.length+iv.length+1] = (byte) pcfb.encipher((byte)length);
		pcfb.blockEncipher(output, 0, output.length);
		System.arraycopy(output, 0, data, hash.length+iv.length+2, output.length);
		byte[] random = new byte[paddingLength];
		node.fastWeakRandom.nextBytes(random);
		System.arraycopy(random, 0, data, hash.length+iv.length+2+output.length, random.length);
		node.nodeStats.reportAuthBytes(data.length + sock.getHeadersLength());
		try {
			sendPacket(data, replyTo, pn);
		} catch (LocalAddressException e) {
			Logger.warning(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn+" - maybe you should set allowLocalAddresses for this peer??");
		}
	}

	private void sendPacket(byte[] data, Peer replyTo, PeerNode pn) throws LocalAddressException {
		if(pn != null) {
			if(pn.isIgnoreSource()) {
				Peer p = pn.getPeer();
				if(p != null) replyTo = p;
			}
		}
		sock.sendPacket(data, replyTo, pn == null ? crypto.config.alwaysAllowLocalAddresses() : pn.allowLocalAddresses());
		if(pn != null)
			pn.reportOutgoingPacket(data, 0, data.length, System.currentTimeMillis());
		if(PeerNode.shouldThrottle(replyTo, node)) {
			node.outputThrottle.forceGrab(data.length);
		}
	}

	/**
	 * Should we log an error for an event that could easily be
	 * caused by a handshake across a restart boundary?
	 */
	private boolean shouldLogErrorInHandshake(long now) {
		if(now - node.startupTime < Node.HANDSHAKE_TIMEOUT*2)
			return false;
		return true;
	}

	/**
	 * Try to process an incoming packet with a given PeerNode.
	 * We need to know where the packet has come from in order to
	 * decrypt and authenticate it.
	 */
	private boolean tryProcess(byte[] buf, int offset, int length, SessionKey tracker, long now) {
		// Need to be able to call with tracker == null to simplify code above
		if(tracker == null) {
			if(logDEBUG) Logger.debug(this, "Tracker == null");
			return false;
		}
		if(logDEBUG) Logger.debug(this,"Entering tryProcess: "+Fields.hashCode(buf)+ ',' +offset+ ',' +length+ ',' +tracker);
		/**
		 * E_pcbc_session(H(seq+random+data)) E_pcfb_session(seq+random+data)
		 *
		 * So first two blocks are the hash, PCBC encoded (meaning the
		 * first one is ECB, and the second one is ECB XORed with the
		 * ciphertext and plaintext of the first block).
		 */
		BlockCipher sessionCipher = tracker.incommingCipher;
		if(sessionCipher == null) {
			if(logMINOR) Logger.minor(this, "No cipher");
			return false;
		}
		if(logDEBUG) Logger.debug(this, "Decrypting with "+HexUtil.bytesToHex(tracker.incommingKey));
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
		// Set IV to the hash, after it is encrypted
		pcfb = PCFBMode.create(sessionCipher, packetHash);
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

		PacketTracker packets = tracker.packets;

		int targetSeqNumber = packets.highestReceivedIncomingSeqNumber();
		if(logDEBUG) Logger.debug(this, "Seqno: "+seqNumber+" (highest seen "+targetSeqNumber+") receiving packet from "+tracker.pn.getPeer());

		if(seqNumber == -1) {
			// Ack/resendreq-only packet
		} else {
			// Now is it credible?
			// As long as it's within +/- 256, this is valid.
			if((targetSeqNumber != -1) && (Math.abs(targetSeqNumber - seqNumber) > MAX_PACKETS_IN_FLIGHT)) {
				return false;
			}
		}
		if(logDEBUG) Logger.debug(this, "Sequence number received: "+seqNumber);

		// Plausible, so lets decrypt the rest of the data

		byte[] plaintext = new byte[length-(4+HASH_LENGTH)];
		System.arraycopy(buf, offset+HASH_LENGTH+4, plaintext, 0, length-(HASH_LENGTH+4));

		pcfb.blockDecipher(plaintext, 0, length-(HASH_LENGTH+4));

		//Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));

		MessageDigest md = SHA256.getMessageDigest();
		md.update(seqBuf);
		md.update(plaintext);
		byte[] realHash = md.digest();
		SHA256.returnMessageDigest(md); md = null;

		// Now decrypt the original hash

		byte[] temp = new byte[blockSize];
		System.arraycopy(buf, offset, temp, 0, blockSize);
		sessionCipher.decipher(temp, temp);
		System.arraycopy(temp, 0, packetHash, 0, blockSize);

		// Check the hash
		if(!Arrays.equals(packetHash, realHash)) {
			if(logDEBUG) Logger.debug(this, "Packet possibly from "+tracker+" hash does not match:\npacketHash="+
					HexUtil.bytesToHex(packetHash)+"\n  realHash="+HexUtil.bytesToHex(realHash)+" ("+(length-HASH_LENGTH)+" bytes payload)");
			return false;
		}

		// Verify
		tracker.pn.verified(tracker);

		for(int i=0;i<HASH_LENGTH;i++) {
			packetHash[i] ^= buf[offset+i];
		}
		if(logDEBUG) Logger.minor(this, "Contributing entropy");
		node.random.acceptEntropyBytes(myPacketDataSource, packetHash, 0, HASH_LENGTH, 0.5);
		if(logDEBUG) Logger.minor(this, "Contributed entropy");

		// Lots more to do yet!
		processDecryptedData(plaintext, seqNumber, tracker, length - plaintext.length);
		tracker.pn.reportIncomingPacket(buf, offset, length, now);
		return true;
	}

	/**
	 * Process an incoming packet, once it has been decrypted.
	 * @param decrypted The packet's contents.
	 * @param seqNumber The detected sequence number of the packet.
	 * @param tracker The SessionKey responsible for the key used to encrypt the packet.
	 */
	private void processDecryptedData(byte[] decrypted, int seqNumber, SessionKey tracker, int overhead) {
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
		if(logMINOR) Logger.minor(this, "Highest sent sequence number: "+realSeqNumber);

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

		PacketTracker packets = tracker.packets;
		if(packets.acknowledgedPackets(acks))
			tracker.pn.receivedAck(System.currentTimeMillis());

		int retransmitCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Retransmit requests: "+retransmitCount);

		for(int i=0;i<retransmitCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int realSeqNo = referenceSeqNumber - offset;
			if(logMINOR) Logger.minor(this, "RetransmitRequest: "+realSeqNo);
			packets.resendPacket(realSeqNo);
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
			packets.receivedAckRequest(realSeqNo);
		}

		int forgottenCount = decrypted[ptr++] & 0xff;
		if(logMINOR) Logger.minor(this, "Forgotten packets: "+forgottenCount);

		for(int i=0;i<forgottenCount;i++) {
			int offset = decrypted[ptr++] & 0xff;
			if(ptr > decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int realSeqNo = realSeqNumber - offset;
			packets.destForgotPacket(realSeqNo);
		}

		tracker.pn.receivedPacket(false, true); // Must keep the connection open, even if it's an ack packet only and on an incompatible connection - we may want to do a UOM transfer e.g.
//		System.err.println(tracker.pn.getIdentityString()+" : received packet");

		// No sequence number == no messages

		if((seqNumber != -1) && packets.alreadyReceived(seqNumber)) {
			packets.queueAck(seqNumber); // Must keep the connection open!
			if(logMINOR) Logger.minor(this, "Received packet twice ("+seqNumber+") from "+tracker.pn.getPeer()+": "+seqNumber+" ("+TimeUtil.formatTime((long) tracker.pn.averagePingTime(), 2, true)+" ping avg)");
			return;
		}

		packets.receivedPacket(seqNumber);

		if(seqNumber == -1) {
			if(logMINOR) Logger.minor(this, "Returning because seqno = "+seqNumber);
			return;
		}

		int messages = decrypted[ptr++] & 0xff;

		overhead += ptr;

		DecodingMessageGroup group = tracker.pn.startProcessingDecryptedMessages(messages);
		for(int i=0;i<messages;i++) {
			if(ptr+1 >= decrypted.length) {
				Logger.error(this, "Packet not long enough at byte "+ptr+" on "+tracker);
			}
			int length = ((decrypted[ptr++] & 0xff) << 8) +
			(decrypted[ptr++] & 0xff);
			if(length > decrypted.length - ptr) {
				Logger.error(this, "Message longer than remaining space: "+length);
				group.complete();
				return;
			}
			if(logMINOR) Logger.minor(this, "Message "+i+" length "+length+", hash code: "+Fields.hashCode(decrypted, ptr, length));
			group.processDecryptedMessage(decrypted, ptr, length, 1 + (overhead / messages));
			ptr+=length;
		}
		group.complete();

		tracker.pn.maybeRekey();
		if(logMINOR) Logger.minor(this, "Done");
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoingOrRequeue(freenet.node.MessageItem[], freenet.node.PeerNode, boolean, boolean)
	 */
	@Override
	public boolean processOutgoingOrRequeue(MessageItem[] messages, PeerNode pn, boolean dontRequeue, boolean onePacket) throws BlockedTooLongException {
		String requeueLogString = "";
		if(!dontRequeue) {
			requeueLogString = ", requeueing";
		}
		if(logMINOR) Logger.minor(this, "processOutgoingOrRequeue "+messages.length+" messages for "+pn);
		byte[][] messageData = new byte[messages.length][];
		MessageItem[] newMsgs = new MessageItem[messages.length];
		SessionKey kt = pn.getCurrentKeyTracker();
		if(kt == null) {
			Logger.error(this, "Not connected while sending packets: "+pn);
			if(!dontRequeue) {
				for(MessageItem item : messages)
					item.onDisconnect();
			}
			return false;
		}
		PacketTracker packets = kt.packets;
		if(packets.wouldBlock(false)) {
			if(logMINOR) Logger.minor(this, "Would block: "+kt);
			// Requeue
			if(!dontRequeue) {
				pn.requeueMessageItems(messages, 0, messages.length, false, "WouldBlock");
			}
			return false;
		}
		int length = 1;
		length += packets.countAcks() + packets.countAckRequests() + packets.countResendRequests();
		int callbacksCount = 0;
		int x = 0;
		String mi_name = null;
		for(int i=0;i<messageData.length;i++) {
			MessageItem mi = messages[i];
			if(logMINOR) Logger.minor(this, "Handling "+(mi.formatted ? "formatted " : "") +
					"MessageItem "+mi+" : "+mi.getLength());
			mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
			if(mi.formatted) {
				try {
					byte[] buf = mi.getData();
					int packetNumber = packets.allocateOutgoingPacketNumberNeverBlock();
					int size = processOutgoingPreformatted(buf, 0, buf.length, kt, packetNumber, mi.cb, mi.getPriority());
					//MARK: onSent()
					mi.onSent(size);
				} catch (NotConnectedException e) {
					Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(newMsgs, 0, x, false, "NotConnectedException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "NotConnectedException(1b)");
					}
					return false;
				} catch (WouldBlockException e) {
					if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(newMsgs, 0, x, false, "WouldBlockException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "WouldBlockException(1b)");
					}
					return false;
				} catch (KeyChangedException e) {
					if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(newMsgs, 0, x, false, "KeyChangedException(1a)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "KeyChangedException(1b)");
					}
					return false;
				} catch (Throwable e) {
					Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
					// Requeue
					if(!dontRequeue) {
						pn.requeueMessageItems(newMsgs, 0, x, false, "Throwable(1)");
						pn.requeueMessageItems(messages, i, messages.length-i, false, "Throwable(1)");
					}
					return false;
				}
			} else {
				byte[] data = mi.getData();
				messageData[x] = data;
				if(data.length > sock.getMaxPacketSize()) {
					Logger.error(this, "Message exceeds packet size: "+messages[i]+" size "+data.length+" message "+mi.msg);
					// Will be handled later
				}
				newMsgs[x] = mi;
				x++;
				if(mi.cb != null) callbacksCount += mi.cb.length;
				if(logMINOR) Logger.minor(this, "Sending: "+mi+" length "+data.length+" cb "+ Arrays.toString(mi.cb));
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
		short priority = DMT.PRIORITY_BULK_DATA;
		for(int i=0;i<messages.length;i++) {
			assert(!messages[i].formatted);
			if(messages[i].cb != null) {
				System.arraycopy(messages[i].cb, 0, callbacks, x, messages[i].cb.length);
				x += messages[i].cb.length;
			}
			short messagePrio = messages[i].getPriority();
			if(messagePrio < priority) priority = messagePrio;
		}
		if(x != callbacksCount) throw new IllegalStateException();

		if((length + HEADERS_LENGTH_MINIMUM < sock.getMaxPacketSize()) &&
				(messageData.length < 256)) {
			mi_name = null;
			try {
				int size = innerProcessOutgoing(messageData, 0, messageData.length, length, pn, callbacks, priority);
				int totalMessageSize = 0;
				for(int i=0;i<messageData.length;i++) totalMessageSize += messageData[i].length;
				int overhead = size - totalMessageSize;
				if(logMINOR) Logger.minor(this, "Overhead: "+overhead+" total messages size "+totalMessageSize+" for "+messageData.length+" messages");
				for(int i=0;i<messageData.length;i++) {
					MessageItem mi = newMsgs[i];
					mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
					//FIXME: This onSent() is called before the (MARK:'d) onSent above for the same message item. Shouldn't they be mutually exclusive?
					mi.onSent(messageData[i].length+(overhead/messageData.length));
				}
			} catch (NotConnectedException e) {
				Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "NotConnectedException(2)");
				return false;
			} catch (WouldBlockException e) {
				if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "WouldBlockException(2)");
				return false;
			} catch (Throwable e) {
				Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
				// Requeue
				if(!dontRequeue)
					pn.requeueMessageItems(messages, 0, messages.length, false, "Throwable(2)");
				return false;

			}
		} else {
			if(!dontRequeue) {
				requeueLogString = ", requeueing remaining messages";
			}
			length = 1;
			length += packets.countAcks() + packets.countAckRequests() + packets.countResendRequests();
			int count = 0;
			int lastIndex = 0;
			if(logMINOR) Logger.minor(this, "Sending "+messageData.length+" messages");
			for(int i=0;i<=messageData.length;i++) {
				if(logMINOR) Logger.minor(this, "Sending message "+i);
				int thisLength;
				if(i == messages.length) thisLength = 0;
				else thisLength = (messageData[i].length + 2);
				int newLength = length + thisLength;
				count++;
				if((newLength + HEADERS_LENGTH_MINIMUM > sock.getMaxPacketSize()) || (count > 255) || (i == messages.length)) {
					// lastIndex up to the message right before this one
					// e.g. lastIndex = 0, i = 1, we just send message 0
					if(lastIndex != i) {
						mi_name = null;
						try {
							// FIXME regenerate callbacks and priority!
							int size = innerProcessOutgoing(messageData, lastIndex, i-lastIndex, length, pn, callbacks, priority);
							int totalMessageSize = 0;
							for(int j=lastIndex;j<i;j++) totalMessageSize += messageData[j].length;
							int overhead = size - totalMessageSize;
							for(int j=lastIndex;j<i;j++) {
								MessageItem mi = newMsgs[j];
								mi_name = (mi.msg == null ? "(not a Message)" : mi.msg.getSpec().getName());
								mi.onSent(messageData[j].length + (overhead / (i-lastIndex)));
							}
						} catch (NotConnectedException e) {
							Logger.normal(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString);
							// Requeue
							if(!dontRequeue) {
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "NotConnectedException(3)");
							}
							return false;
						} catch (WouldBlockException e) {
							if(logMINOR) Logger.minor(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
							// Requeue
							if(!dontRequeue) {
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "WouldBlockException(3)");
							}
							return false;
						} catch (Throwable e) {
							Logger.error(this, "Caught "+e+" while sending messages ("+mi_name+") to "+pn.getPeer()+requeueLogString, e);
							// Requeue
							if(!dontRequeue) {
								pn.requeueMessageItems(messages, lastIndex, messages.length - lastIndex, false, "Throwable(3)");
							}
							return false;
						}
						if(onePacket) {
							pn.requeueMessageItems(messages, i, messageData.length - i, true, "Didn't fit in single packet");
							return false;
						}
					}
					lastIndex = i;
					if(i != messageData.length) {
						length = 1 + (messageData[i].length + 2);
					}
					count = 0;
				} else {
					length = newLength;
				}
			}
		}
		return true;
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
	private int innerProcessOutgoing(byte[][] messageData, int start, int length, int bufferLength,
			PeerNode pn, AsyncMessageCallback[] callbacks, short priority) throws NotConnectedException, WouldBlockException, PacketSequenceException {
		if(logMINOR) Logger.minor(this, "innerProcessOutgoing(...,"+start+ ',' +length+ ',' +bufferLength+ ','+callbacks.length+')');
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
		if(logMINOR) Logger.minor(this, "Packed data is "+loc+" bytes long.");
		return processOutgoingPreformatted(buf, 0, loc, pn, callbacks, priority);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoing(byte[], int, int, freenet.node.SessionKey, int)
	 */
	@Override
	public int processOutgoing(byte[] buf, int offset, int length, SessionKey tracker, short priority) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
		byte[] newBuf = preformat(buf, offset, length);
		return processOutgoingPreformatted(newBuf, 0, newBuf.length, tracker, -1, null, priority);
	}

	/**
	 * Send a packet using the current key. Retry if it fails solely because
	 * the key changes.
	 * @throws PacketSequenceException
	 */
	int processOutgoingPreformatted(byte[] buf, int offset, int length, PeerNode peer, AsyncMessageCallback[] callbacks, short priority) throws NotConnectedException, WouldBlockException, PacketSequenceException {
		SessionKey last = null;
		while(true) {
			try {
				if(!peer.isConnected())
					throw new NotConnectedException();
				SessionKey tracker = peer.getCurrentKeyTracker();
				last = tracker;
				if(tracker == null) {
					Logger.normal(this, "Dropping packet: Not connected to "+peer.getPeer()+" yet(2)");
					throw new NotConnectedException();
				}
				PacketTracker packets = tracker.packets;
				int seqNo = packets.allocateOutgoingPacketNumberNeverBlock();
				return processOutgoingPreformatted(buf, offset, length, tracker, seqNo, callbacks, priority);
			} catch (KeyChangedException e) {
				Logger.normal(this, "Key changed(2) for "+peer.getPeer());
				if(last == peer.getCurrentKeyTracker()) {
					if(peer.isConnected()) {
						Logger.error(this, "Peer is connected, yet current tracker is deprecated !! (rekey ?): "+e, e);
						throw new NotConnectedException("Peer is connected, yet current tracker is deprecated !! (rekey ?): "+e);
					}
				}
				// Go around again
			}
		}
	}

	byte[] preformat(byte[] buf, int offset, int length) {
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

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#processOutgoingPreformatted(byte[], int, int, freenet.node.SessionKey, int, freenet.node.AsyncMessageCallback[], int)
	 */
	@Override
	public int processOutgoingPreformatted(byte[] buf, int offset, int length, SessionKey tracker, int packetNumber, AsyncMessageCallback[] callbacks, short priority) throws KeyChangedException, NotConnectedException, PacketSequenceException, WouldBlockException {
		if(logMINOR) {
			String log = "processOutgoingPreformatted("+Fields.hashCode(buf)+", "+offset+ ',' +length+ ',' +tracker+ ',' +packetNumber+ ',';
			if(callbacks == null) log += "null";
			else log += ""+callbacks.length+Arrays.toString(callbacks); // FIXME too verbose?
			Logger.minor(this, log);
		}
		if((tracker == null) || (!tracker.pn.isConnected())) {
			throw new NotConnectedException();
		}

		// We do not support forgotten packets at present

		int[] acks, resendRequests, ackRequests, forgotPackets;
		int seqNumber;
		PacketTracker packets = tracker.packets;
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

		if(packetNumber > 0) {
			seqNumber = packetNumber;
		} else {
			if(buf.length == 1) {
				// Ack/resendreq only packet
				seqNumber = -1;
			} else {
				seqNumber = packets.allocateOutgoingPacketNumberNeverBlock();
			}
		}

		if(logMINOR) Logger.minor(this, "Sequence number (sending): "+seqNumber+" ("+packetNumber+") to "+tracker.pn.getPeer());

		/** The last sent sequence number, so that we can refer to packets
		 * sent after this packet was originally sent (it may be a resend) */
		int realSeqNumber;

		int otherSideSeqNumber;

		try {
		synchronized(tracker) {
			acks = packets.grabAcks();
			forgotPackets = packets.grabForgotten();
			resendRequests = packets.grabResendRequests();
			ackRequests = packets.grabAckRequests();
			realSeqNumber = packets.getLastOutgoingSeqNumber();
			otherSideSeqNumber = packets.highestReceivedIncomingSeqNumber();
			if(logMINOR) Logger.minor(this, "Sending packet to "+tracker.pn.getPeer()+", other side max seqno: "+otherSideSeqNumber);
		}
		} catch (StillNotAckedException e) {
			Logger.error(this, "Forcing disconnect on "+tracker.pn+" for "+tracker+" because packets not acked after 10 minutes!");
			tracker.pn.forceDisconnect();
			disconnectedStillNotAcked(tracker);
			throw new NotConnectedException();
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
		1 + // number of forgotten packets
		forgotPackets.length +
		length; // the payload !
		
		if(logMINOR)
			Logger.minor(this, "Fully packed data is "+packetLength+" bytes long");

		boolean paddThisPacket = crypto.config.paddDataPackets();
		int paddedLen;
		if(paddThisPacket) {
			if(logMINOR)
				Logger.minor(this, "Pre-padding length: " + packetLength);

			// Padding
			// This will do an adequate job of disguising the contents, and a poor (but not totally
			// worthless) job of disguising the traffic. FIXME!!!!!
			// Ideally we'd mimic the size profile - and the session bytes! - of a common protocol.

			if(packetLength < 64) {
				// Up to 37 bytes of payload (after base overhead above of 27 bytes), padded size 96-128 bytes.
				// Most small messages, and most ack only packets.
				paddedLen = 64 + tracker.pn.paddingGen.nextInt(32);
			} else {
				// Up to 69 bytes of payload, final size 128-192 bytes (CHK request, CHK insert, opennet announcement, CHK offer, swap reply)
				// Up to 133 bytes of payload, final size 192-256 bytes (SSK request, get offered CHK, offer SSK[, SSKInsertRequestNew], get offered SSK)
				// Up to 197 bytes of payload, final size 256-320 bytes (swap commit/complete[, SSKDataFoundNew, SSKInsertRequestAltNew])
				// Up to 1093 bytes of payload, final size 1152-1216 bytes (bulk transmit, block transmit, time deltas, SSK pubkey[, SSKData, SSKDataInsert])
				packetLength += 32;
				paddedLen = ((packetLength + 63) / 64) * 64;
				paddedLen += tracker.pn.paddingGen.nextInt(64);
				// FIXME get rid of this, we shouldn't be sending packets anywhere near this size unless
				// we've done PMTU...
				if(packetLength <= 1280 && paddedLen > 1280)
					paddedLen = 1280;
				int maxPacketSize = sock.getMaxPacketSize();
				if(packetLength <= maxPacketSize && paddedLen > maxPacketSize)
					paddedLen = maxPacketSize;
				packetLength -= 32;
				paddedLen -= 32;
			}
		} else {
			if(logMINOR)
				Logger.minor(this, "Don't padd the packet: we have been asked not to.");
			paddedLen = packetLength;
		}

		if(paddThisPacket)
			packetLength = paddedLen;

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

		byte[] forgotOffsets = null;
		int forgotCount = 0;

		if(forgotPackets.length > 0) {
			for(int i=0;i<forgotPackets.length;i++) {
				int seq = forgotPackets[i];
				if(logMINOR) Logger.minor(this, "Forgot packet "+i+": "+seq);
				int offsetSeq = realSeqNumber - seq;
				if((offsetSeq > 255) || (offsetSeq < 0)) {
					if(packets.isDeprecated()) {
						// Oh well
						Logger.error(this, "Dropping forgot-packet notification on deprecated tracker: "+seq+" on "+tracker+" - real seq="+realSeqNumber);
						// Ignore it
						continue;
					} else {
						Logger.error(this, "bad forgot packet offset: "+offsetSeq+
								" - forgotSeq="+seq+", packetNumber="+realSeqNumber+" talking to "+tracker.pn.getPeer(), new Exception("error"));
					}
				} else {
					if(forgotOffsets == null)
						forgotOffsets = new byte[forgotPackets.length - i];

					if(forgotCount >= 256) {
						packets.requeueForgot(forgotPackets, forgotCount, forgotPackets.length - forgotCount);
						break;
					} else {
						forgotOffsets[forgotCount++] = (byte) offsetSeq;
					}
				}
			}
			if(forgotCount >= 256) forgotCount = 255;
		}

		plaintext[ptr++] = (byte) forgotCount;

		if(forgotOffsets != null) {
			System.arraycopy(forgotOffsets, 0, plaintext, ptr, forgotCount);
			ptr += forgotCount;
		}

		System.arraycopy(buf, offset, plaintext, ptr, length);
		ptr += length;

		if(paddThisPacket) {
			byte[] padding = new byte[packetLength - ptr];
			tracker.pn.paddingGen.nextBytes(padding);

			System.arraycopy(padding, 0, plaintext, ptr, padding.length);
			ptr += padding.length;
		} else if(ptr != plaintext.length) {
			Logger.error(this, "Inconsistent length: "+plaintext.length+" buffer but "+(ptr)+" actual");
			byte[] newBuf = new byte[ptr];
			System.arraycopy(plaintext, 0, newBuf, 0, ptr);
			plaintext = newBuf;
		}

		if(seqNumber != -1) {
			byte[] saveable = new byte[length];
			System.arraycopy(buf, offset, saveable, 0, length);
			packets.sentPacket(saveable, seqNumber, callbacks, priority);
		}

		if(logMINOR) Logger.minor(this, "Sending... "+seqNumber);

		int ret = processOutgoingFullyFormatted(plaintext, tracker);
		if(logMINOR) Logger.minor(this, "Sent packet "+seqNumber);
		return ret;
	}

	private HashSet<Peer> peersWithProblems = new HashSet<Peer>();

	private void disconnectedStillNotAcked(SessionKey tracker) {
		synchronized(peersWithProblems) {
			peersWithProblems.add(tracker.pn.getPeer());
			if(peersWithProblems.size() > 1) return;
		}
		if(node.clientCore == null || node.clientCore.alerts == null)
			return;
		// FIXME XXX: We have had this alert enabled for MONTHS which got us hundreds of bug reports about it. Unfortunately, nobody spend any work on fixing
		// the issue after the alert was added so I have disabled it to quit annoying our users. We should not waste their time if we don't do anything. xor
		// Notice that the same alert is commented out in PacketSender.
		// node.clientCore.alerts.register(disconnectedStillNotAckedAlert);
	}

	@SuppressWarnings("unused")
	private UserAlert disconnectedStillNotAckedAlert = new AbstractUserAlert() {

		@Override
		public String anchor() {
			return "disconnectedStillNotAcked";
		}

		@Override
		public String dismissButtonText() {
			return NodeL10n.getBase().getString("UserAlert.hide");
		}

		@Override
		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		@Override
		public String getShortText() {
			int sz;
			synchronized(peersWithProblems) {
				sz = peersWithProblems.size();
			}
			return l10n("somePeersDisconnectedStillNotAcked", "count", Integer.toString(sz));
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			Peer[] peers;
			synchronized(peersWithProblems) {
				peers = peersWithProblems.toArray(new Peer[peersWithProblems.size()]);
			}
			NodeL10n.getBase().addL10nSubstitution(div,
			        "FNPPacketMangler.somePeersDisconnectedStillNotAckedDetail",
			        new String[] { "count", "link" },
			        new HTMLNode[] { HTMLNode.text(peers.length),
			                HTMLNode.link(ExternalLinkToadlet.escape("https://bugs.freenetproject.org/view.php?id=2692")) });
			HTMLNode list = div.addChild("ul");
			for(Peer peer : peers) {
				list.addChild("li", peer.toString());
			}
			return div;
		}

		@Override
		public String getText() {
			StringBuffer sb = new StringBuffer();
			Peer[] peers;
			synchronized(peersWithProblems) {
				peers = peersWithProblems.toArray(new Peer[peersWithProblems.size()]);
			}
			sb.append(l10n("somePeersDisconnectedStillNotAckedDetail",
					new String[] { "count", "link", "/link" },
					new String[] { Integer.toString(peers.length), "", "" } ));
			sb.append('\n');
			for(Peer peer : peers) {
				sb.append('\t');
				sb.append(peer.toString());
				sb.append('\n');
			}
			return sb.toString();
		}

		@Override
		public String getTitle() {
			return getShortText();
		}

		@Override
		public Object getUserIdentifier() {
			return FNPPacketMangler.this;
		}

		@Override
		public boolean isEventNotification() {
			return false;
		}

		@Override
		public boolean isValid() {
			return true;
		}

		@Override
		public void isValid(boolean validity) {
			// Ignore
		}

		@Override
		public void onDismiss() {
			// Ignore
		}

		@Override
		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		@Override
		public boolean userCanDismiss() {
			return true;
		}

	};

	/**
	 * Encrypt and send a packet.
	 * @param plaintext The packet's plaintext, including all formatting,
	 * including acks and resend requests. Is clobbered.
	 */
	private int processOutgoingFullyFormatted(byte[] plaintext, SessionKey kt) {
		BlockCipher sessionCipher = kt.outgoingCipher;
		if(logMINOR) Logger.minor(this, "Encrypting with "+HexUtil.bytesToHex(kt.outgoingKey));
		if(sessionCipher == null) {
			Logger.error(this, "Dropping packet send - have not handshaked yet");
			return 0;
		}
		int blockSize = sessionCipher.getBlockSize() >> 3;
		if(sessionCipher.getKeySize() != sessionCipher.getBlockSize()) {
			throw new IllegalStateException("Block size must be half key size: blockSize="+
					sessionCipher.getBlockSize()+", keySize="+sessionCipher.getKeySize());
		}

		MessageDigest md = SHA256.getMessageDigest();

		int digestLength = md.getDigestLength();

		if(digestLength != blockSize) {
			throw new IllegalStateException("Block size must be digest length!");
		}

		byte[] output = new byte[plaintext.length + digestLength];
		System.arraycopy(plaintext, 0, output, digestLength, plaintext.length);

		md.update(plaintext);

		//Logger.minor(this, "Plaintext:\n"+HexUtil.bytesToHex(plaintext));

		byte[] digestTemp;

		digestTemp = md.digest();

		SHA256.returnMessageDigest(md); md = null;

		if(logMINOR) Logger.minor(this, "\nHash:      "+HexUtil.bytesToHex(digestTemp));

		// Put encrypted digest in output
		sessionCipher.encipher(digestTemp, digestTemp);

		// Now copy it back
		System.arraycopy(digestTemp, 0, output, 0, digestLength);
		// Yay, we have an encrypted hash

		if(logMINOR) Logger.minor(this, "\nEncrypted: "+HexUtil.bytesToHex(digestTemp)+" ("+plaintext.length+" bytes plaintext)");

		PCFBMode pcfb = PCFBMode.create(sessionCipher, digestTemp);
		pcfb.blockEncipher(output, digestLength, plaintext.length);

		//Logger.minor(this, "Ciphertext:\n"+HexUtil.bytesToHex(output, digestLength, plaintext.length));

		// We have a packet
		// Send it

		if(logMINOR) Logger.minor(this,"Sending packet of length "+output.length+" (" + Fields.hashCode(output) + ") to "+kt.pn);

		// pn.getPeer() cannot be null
		try {
			sendPacket(output, kt.pn.getPeer(), kt.pn);
//			System.err.println(kt.pn.getIdentityString()+" : sent packet length "+output.length);
		} catch (LocalAddressException e) {
			Logger.error(this, "Tried to send data packet to local address: "+kt.pn.getPeer()+" for "+kt.pn.allowLocalAddresses());
		}
		kt.pn.sentPacket();
		return output.length + sock.getHeadersLength();
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, pattern, value);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#sendHandshake(freenet.node.PeerNode)
	 */
	@Override
	public void sendHandshake(PeerNode pn, boolean notRegistered) {
		int negType = pn.selectNegType(this);
		if(negType == -1) {
			// Pick a random negType from what I do support
			int[] negTypes = supportedNegTypes(true);
			negType = negTypes[node.random.nextInt(negTypes.length)];
			Logger.normal(this, "Cannot send handshake to "+pn+" because no common negTypes, choosing random negType of "+negType);
		}
		if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn+" negotiation type "+negType);

		Peer peer = pn.getHandshakeIP();
		if(peer == null) {
			pn.couldNotSendHandshake(notRegistered);
			return;
		}
		Peer oldPeer = peer;
		peer = peer.dropHostName();
		if(peer == null) {
			Logger.error(this, "No address for peer "+oldPeer+" so cannot send handshake");
			pn.couldNotSendHandshake(notRegistered);
			return;
		}
		sendJFKMessage1(pn, peer, pn.handshakeUnknownInitiator(), pn.handshakeSetupType(), negType);
		if(logMINOR)
			Logger.minor(this, "Sending handshake to "+peer+" for "+pn);
		pn.sentHandshake(notRegistered);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#isDisconnected(freenet.io.comm.PeerContext)
	 */
	@Override
	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !context.isConnected();
	}

	@Override
	public void resend(ResendPacketItem item, SessionKey tracker) throws PacketSequenceException, WouldBlockException, KeyChangedException, NotConnectedException {
		int size = processOutgoingPreformatted(item.buf, 0, item.buf.length, tracker, item.packetNumber, item.callbacks, item.priority);
		item.pn.resendByteCounter.sentBytes(size);
	}

	@Override
	public int[] supportedNegTypes(boolean forPublic) {
		if(forPublic)
			return new int[] { 2, 4, 6, 7 };
		else
			return new int[] { 2, 4, 6, 7 };
	}

	@Override
	public int fullHeadersLengthOneMessage() {
		return fullHeadersLengthOneMessage;
	}

	@Override
	public SocketHandler getSocketHandler() {
		return sock;
	}

	@Override
	public Peer[] getPrimaryIPAddress() {
		return crypto.detector.getPrimaryPeers();
	}

	@Override
	public byte[] getCompressedNoderef() {
		return crypto.myCompressedFullRef();
	}

	@Override
	public boolean alwaysAllowLocalAddresses() {
		return crypto.config.alwaysAllowLocalAddresses();
	}

	private DiffieHellmanLightContext _genLightDiffieHellmanContext() {
		final DiffieHellmanLightContext ctx = DiffieHellman.generateLightContext();
		ctx.setSignature(crypto.sign(SHA256.digest(assembleDHParams(ctx.myExponential, crypto.getCryptoGroup()))));

		return ctx;
	}

	private void _fillJFKDHFIFOOffThread() {
		// do it off-thread
		node.executor.execute(new PrioRunnable() {
			@Override
			public void run() {
				_fillJFKDHFIFO();
			}
			@Override
			public int getPriority() {
				return NativeThread.HIGH_PRIORITY;
			}
		}, "DiffieHellman exponential signing");
	}

	private void _fillJFKDHFIFO() {
		synchronized (dhContextFIFO) {
			if(dhContextFIFO.size() + 1 > DH_CONTEXT_BUFFER_SIZE) {
				DiffieHellmanLightContext result = null;
				long oldestSeen = Long.MAX_VALUE;

				for (DiffieHellmanLightContext tmp: dhContextFIFO) {
					if(tmp.lifetime < oldestSeen) {
						oldestSeen = tmp.lifetime;
						result = tmp;
					}
				}
				dhContextFIFO.remove(dhContextToBePrunned = result);
			}

			dhContextFIFO.addLast(_genLightDiffieHellmanContext());
		}
	}

	/**
	 * Change the DH Exponents on a regular basis but at most once every 30sec
	 *
	 * @return {@link DiffieHellmanLightContext}
	 */
	private DiffieHellmanLightContext getLightDiffieHellmanContext() {
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext result = null;

		synchronized (dhContextFIFO) {
			result = dhContextFIFO.removeFirst();

			// Shall we replace one element of the queue ?
			if((jfkDHLastGenerationTimestamp + DH_GENERATION_INTERVAL) < now) {
				jfkDHLastGenerationTimestamp = now;
				_fillJFKDHFIFOOffThread();
			}

			dhContextFIFO.addLast(result);
		}

		Logger.minor(this, "getLightDiffieHellmanContext() is serving "+result.hashCode());
		return result;
	}

	/**
	 * Used in processJFK[3|4]
	 * That's O^(n) ... but we have only a few elements and
	 * we call it only once a round-trip has been done
	 *
	 * @param exponential
	 * @return the corresponding DiffieHellmanLightContext with the right exponent
	 */
	private DiffieHellmanLightContext findContextByExponential(BigInteger exponential) {
		synchronized (dhContextFIFO) {
			for (DiffieHellmanLightContext result : dhContextFIFO) {
				if(exponential.equals(result.myExponential)) {
					return result;
				}
			}

			if((dhContextToBePrunned != null) && ((dhContextToBePrunned.myExponential).equals(exponential)))
				return dhContextToBePrunned;
		}
		return null;
	}

	/*
	 * Prepare DH parameters of message2 for them to be signed (useful in message3 to check the sig)
	 */
	private byte[] assembleDHParams(BigInteger exponential, DSAGroup group) {
		byte[] _myExponential = stripBigIntegerToNetworkFormat(exponential);
		byte[] _myGroup = group.getP().toByteArray();
		byte[] toSign = new byte[_myExponential.length + _myGroup.length];

		System.arraycopy(_myExponential, 0, toSign, 0, _myExponential.length);
		System.arraycopy(_myGroup, 0, toSign, _myExponential.length, _myGroup.length);

		return toSign;
	}

	private byte[] assembleDHParams(byte[] nonceInitiator,byte[] nonceResponder,BigInteger initiatorExponential, BigInteger responderExponential, byte[] id, byte[] sa) {
		byte[] _initiatorExponential = stripBigIntegerToNetworkFormat(initiatorExponential);
		byte[] _responderExponential = stripBigIntegerToNetworkFormat(responderExponential);
		byte[] result = new byte[nonceInitiator.length + nonceResponder.length + _initiatorExponential.length + _responderExponential.length + id.length + sa.length];
		int offset = 0;

		System.arraycopy(nonceInitiator, 0,result,offset,nonceInitiator.length);
		offset += nonceInitiator.length;
		System.arraycopy(nonceResponder,0 ,result,offset,nonceResponder.length);
		offset += nonceResponder.length;
		System.arraycopy(_initiatorExponential, 0, result,offset, _initiatorExponential.length);
		offset += _initiatorExponential.length;
		System.arraycopy(_responderExponential, 0, result, offset, _responderExponential.length);
		offset += _responderExponential.length;
		System.arraycopy(id, 0, result , offset,id.length);
		offset += id.length;
		System.arraycopy(sa, 0, result , offset,sa.length);

		return result;
	}

	private byte[] getTransientKey() {
		synchronized (authenticatorCache) {
			return transientKey;
		}
	}

	private byte[] computeJFKSharedKey(BigInteger exponential, byte[] nI, byte[] nR, String what) {
		assert("0".equals(what) || "1".equals(what) || "2".equals(what) || "3".equals(what)
				|| "4".equals(what) || "5".equals(what) || "6".equals(what) || "7".equals(what));
		byte[] number = null;
		try {
			number = what.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		byte[] toHash = new byte[NONCE_SIZE * 2 + number.length];
		int offset = 0;
		System.arraycopy(nI, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(nR, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(number, 0, toHash, offset, number.length);

		return HMAC.macWithSHA256(exponential.toByteArray(), toHash, HASH_LENGTH);
	}

	private long timeLastReset = -1;

	/**
	 * How big can the authenticator cache get before we flush it ?
	 * n * 40 bytes (32 for the authenticator and 8 for the timestamp)
	 *
	 * We push to it until we reach the cap where we rekey or we reach the PFS interval
	 */
	private int getAuthenticatorCacheSize() {
		if(crypto.isOpennet && node.wantAnonAuth(true)) // seednodes
			return 5000; // 200kB
		else
			return 250; // 10kB
	}
	
	/**
	 * Change the transient key used by JFK.
	 *
	 * It will determine the PFS interval, hence we call it at least once every 30mins.
	 *
	 * @return True if we reset the transient key and therefore the authenticator cache.
	 */
	private boolean maybeResetTransientKey() {
		long now = System.currentTimeMillis();
		boolean isCacheTooBig = true;
		int authenticatorCacheSize = 0;
		int AUTHENTICATOR_CACHE_SIZE = getAuthenticatorCacheSize();
		synchronized (authenticatorCache) {
			authenticatorCacheSize = authenticatorCache.size();
			if(authenticatorCacheSize < AUTHENTICATOR_CACHE_SIZE) {
				isCacheTooBig = false;
				if(now - timeLastReset < TRANSIENT_KEY_REKEYING_MIN_INTERVAL)
					return false;
			}
			timeLastReset = now;

			node.random.nextBytes(transientKey);

			// reset the authenticator cache
			authenticatorCache.clear();
		}
		node.getTicker().queueTimedJob(transientKeyRekeyer, "JFKmaybeResetTransientKey"+now, TRANSIENT_KEY_REKEYING_MIN_INTERVAL, false, false);
		Logger.normal(this, "JFK's TransientKey has been changed and the message cache flushed because "+(isCacheTooBig ? ("the cache is oversized ("+authenticatorCacheSize+')') : "it's time to rekey")+ " on " + this);
		return true;
	}

	private byte[] stripBigIntegerToNetworkFormat(BigInteger exponential) {
		byte[] data = exponential.toByteArray();
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
		return data;
	}

	@Override
	public Status getConnectivityStatus() {
		long now = System.currentTimeMillis();
		if (now - lastConnectivityStatusUpdate < 3 * 60 * 1000)
			return lastConnectivityStatus;

		Status value;
		if (crypto.config.alwaysHandshakeAggressively())
			value = AddressTracker.Status.DEFINITELY_NATED;
		else
			value = sock.getDetectedConnectivityStatus();

		lastConnectivityStatusUpdate = now;

		return lastConnectivityStatus = value;
	}

	@Override
	public boolean allowConnection(PeerNode pn, FreenetInetAddress addr) {
		return crypto.allowConnection(pn, addr);
	}

	@Override
	public void setPortForwardingBroken() {
		crypto.setPortForwardingBroken();
	}

}
