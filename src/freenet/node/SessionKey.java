/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.HashMap;

import freenet.crypt.BlockCipher;
import freenet.support.Logger;

/**
 * Class representing a single session key.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SessionKey {
	
	/** A PacketTracker may have more than one SessionKey, but a SessionKey 
	 * may only have one PacketTracker. In other words, in some cases it is
	 * possible to change the session key without invalidating the packet
	 * sequence, but it is never possible to invalidate the packet sequence
	 * without changing the session key. */
	final PacketTracker packets;
	
	/** Parent PeerNode */
	public final PeerNode pn;
	/** Cipher to both encrypt outgoing packets with and decrypt
	 * incoming ones. */
	public final BlockCipher sessionCipher;
	/** Key for above cipher, so far for debugging */
	public final byte[] sessionKey;

	public final BlockCipher ivCipher;
	public final byte[] ivNonce;
	public final byte[] hmacKey;
	
	SessionKey(PeerNode parent, PacketTracker tracker, BlockCipher cipher, byte[] sessionKey,
	                BlockCipher ivCipher, byte[] ivNonce, byte[] hmacKey) {
		this.pn = parent;
		this.packets = tracker;
		this.sessionCipher = cipher;
		this.sessionKey = sessionKey;
		this.ivCipher = ivCipher;
		this.ivNonce = ivNonce;
		this.hmacKey = hmacKey;
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+packets.toString();
	}
}
