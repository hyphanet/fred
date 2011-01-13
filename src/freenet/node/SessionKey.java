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
	/** Cipher to encrypt outgoing packets with */
	public final BlockCipher outgoingCipher;
	/** Key for outgoingCipher, so far for debugging */
	public final byte[] outgoingKey;

	/** Cipher to decrypt incoming packets */
	public final BlockCipher incommingCipher;
	/** Key for incommingCipher, so far for debugging */
	public final byte[] incommingKey;

	public final BlockCipher ivCipher;
	public final byte[] ivNonce;
	public final byte[] hmacKey;
	
	public final NewPacketFormatKeyContext packetContext;

	SessionKey(PeerNode parent, PacketTracker tracker, BlockCipher outgoingCipher, byte[] outgoingKey,
	                BlockCipher incommingCipher, byte[] incommingKey, BlockCipher ivCipher,
			byte[] ivNonce, byte[] hmacKey, NewPacketFormatKeyContext context) {
		this.pn = parent;
		this.packets = tracker;
		this.outgoingCipher = outgoingCipher;
		this.outgoingKey = outgoingKey;
		this.incommingCipher = incommingCipher;
		this.incommingKey = incommingKey;
		this.ivCipher = ivCipher;
		this.ivNonce = ivNonce;
		this.hmacKey = hmacKey;
		this.packetContext = context;
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+packets;
	}

	public void disconnected(boolean notPackets) {
		if(!notPackets)
			packets.disconnected();
		packetContext.disconnected();
	}
}
