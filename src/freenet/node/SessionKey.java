/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.crypt.BlockCipher;

/**
 * Class representing a single session key.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class SessionKey {
	
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
	
	final long trackerID;
	
	public final NewPacketFormatKeyContext packetContext;

	SessionKey(PeerNode parent, BlockCipher outgoingCipher, byte[] outgoingKey,
	                BlockCipher incommingCipher, byte[] incommingKey, BlockCipher ivCipher,
			byte[] ivNonce, byte[] hmacKey, NewPacketFormatKeyContext context, long trackerID) {
		this.pn = parent;
		this.outgoingCipher = outgoingCipher;
		this.outgoingKey = outgoingKey;
		this.incommingCipher = incommingCipher;
		this.incommingKey = incommingKey;
		this.ivCipher = ivCipher;
		this.ivNonce = ivNonce;
		this.hmacKey = hmacKey;
		this.packetContext = context;
		this.trackerID = trackerID;
	}
	
	public void disconnected() {
		packetContext.disconnected();
	}
}
