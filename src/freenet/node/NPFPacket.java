/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;

import freenet.crypt.Util;
import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger.LogLevel;

class NPFPacket {
	private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private int sequenceNumber;
	private final SortedSet<Integer> acks = new TreeSet<Integer>();
	private final List<MessageFragment> fragments = new ArrayList<MessageFragment>();
	/** Messages that are specific to a single packet and can be happily lost if it is lost. 
	 * They must be processed before the rest of the messages.
	 * With early versions, these might be bogus, so be careful parsing them. */
	private final List<byte[]> lossyMessages = new LinkedList<byte[]>();
	private boolean error;
	private int length = 5; //Sequence number (4), numAcks(1)
	private int ackRangeCount = 0;
	private int ackBlockByteSize = 0;
	
	public static NPFPacket create(byte[] plaintext, BasePeerNode pn) {
		NPFPacket packet = new NPFPacket();
		if (pn == null) throw new IllegalArgumentException("Can't estimate an ack type of received packet");
		int offset = 0;

		if(plaintext.length < (offset + 5)) { //Sequence number + the number of acks
			packet.error = true;
			return packet;
		}

		packet.sequenceNumber = ((plaintext[offset] & 0xFF) << 24)
		                | ((plaintext[offset + 1] & 0xFF) << 16)
		                | ((plaintext[offset + 2] & 0xFF) << 8)
		                | (plaintext[offset + 3] & 0xFF);
		offset += 4;

		//Process received acks

			int numAckRanges = plaintext[offset++] & 0xFF;
			if (numAckRanges > 0) {
				try {
					int ack, prevAck = 0;
					
					for(int i = 0; i < numAckRanges; i++) {
						if (i == 0) {
							ack = ((plaintext[offset] & 0xFF) << 24)
						               | ((plaintext[offset + 1] & 0xFF) << 16)
						               | ((plaintext[offset + 2] & 0xFF) << 8)
						               | (plaintext[offset + 3] & 0xFF);
							offset += 4;
						} else {
							int distanceFromPrevious = (plaintext[offset++] & 0xFF);
							if (distanceFromPrevious != 0) {
								ack = prevAck + distanceFromPrevious;
							} else {
								// Far offset
								ack = ((plaintext[offset] & 0xFF) << 24)
							               | ((plaintext[offset + 1] & 0xFF) << 16)
							               | ((plaintext[offset + 2] & 0xFF) << 8)
							               | (plaintext[offset + 3] & 0xFF);
								offset += 4;
							}
						}
						
						int rangeSize = (plaintext[offset++] & 0xFF);
						for (int j = 1; j <= rangeSize; j++) {
							packet.acks.add(ack++);
						}
						
						prevAck = ack-1;
					}
				} catch (ArrayIndexOutOfBoundsException e) {
					// The packet's length is not big enough
					packet.error = true;
					return packet;
				}
			}

		//Handle received message fragments
		int prevFragmentID = -1;
		while(offset < plaintext.length) {
			boolean shortMessage = (plaintext[offset] & 0x80) != 0;
			boolean isFragmented = (plaintext[offset] & 0x40) != 0;
			boolean firstFragment = (plaintext[offset] & 0x20) != 0;

			if(!isFragmented && !firstFragment) {
				// Padding or lossy messages.
				offset = tryParseLossyMessages(packet, plaintext, offset);
				break;
			}

			int messageID = -1;
			if((plaintext[offset] & 0x10) != 0) {
				if(plaintext.length < (offset + 4)) {
					packet.error = true;
					return packet;
				}

				messageID = ((plaintext[offset] & 0x0F) << 24)
				                | ((plaintext[offset + 1] & 0xFF) << 16)
				                | ((plaintext[offset + 2] & 0xFF) << 8)
				                | (plaintext[offset + 3] & 0xFF);
				offset += 4;
			} else {
				if(plaintext.length < (offset + 2)) {
					packet.error = true;
					return packet;
				}

				if(prevFragmentID == -1) {
					Logger.warning(NPFPacket.class, "First fragment doesn't have full message id");
					packet.error = true;
					return packet;
				}
				messageID = prevFragmentID + (((plaintext[offset] & 0x0F) << 8)
				                | (plaintext[offset + 1] & 0xFF));
				offset += 2;
			}
			prevFragmentID = messageID;

			int requiredLength = offset
			                + (shortMessage ? 1 : 2)
			                + (isFragmented ? (shortMessage ? 1 : 3) : 0);
			if(plaintext.length < requiredLength) {
				packet.error = true;
				return packet;
			}

			int fragmentLength;
			if(shortMessage) {
				fragmentLength = plaintext[offset++] & 0xFF;
			} else {
				fragmentLength = ((plaintext[offset] & 0xFF) << 8)
				                | (plaintext[offset + 1] & 0xFF);
				offset += 2;
			}

			int messageLength = -1;
			int fragmentOffset = 0;
			if(isFragmented) {
				int value;
				if(shortMessage) {
					value = plaintext[offset++] & 0xFF;
				} else {
					value = ((plaintext[offset] & 0xFF) << 8)
							| (plaintext[offset + 1] & 0xFF);
					offset += 2;
				}

				if(firstFragment) {
					messageLength = value;
					if(messageLength == fragmentLength) {
						Logger.warning(NPFPacket.class, "Received fragmented message, but fragment contains the entire message");
					}
				} else {
					fragmentOffset = value;
				}
			} else {
				messageLength = fragmentLength;
			}
			if((offset + fragmentLength) > plaintext.length) {
				Logger.error(NPFPacket.class, "Fragment doesn't fit in the received packet: offset is "+offset+" fragment length is "+fragmentLength+" plaintext length is "+plaintext.length+" message length "+messageLength+" message ID "+messageID+(pn == null ? "" : (" from "+pn.shortToString())));
				packet.error = true;
				break;
			}
			byte[] fragmentData = Arrays.copyOfRange(plaintext, offset, offset + fragmentLength);
			offset += fragmentLength;

			packet.fragments.add(new MessageFragment(shortMessage, isFragmented, firstFragment,
			                messageID, fragmentLength, messageLength, fragmentOffset, fragmentData, null));
		}
		
		packet.length = offset;

		return packet;
	}

	private static int tryParseLossyMessages(NPFPacket packet,
			byte[] plaintext, int offset) {
		int origOffset = offset;
		while(true) {
			if(plaintext[offset] != 0x1F)
				return offset; // Padding
			// Else it might be some per-packet lossy messages
			offset++;
			if(offset >= plaintext.length) {
				packet.lossyMessages.clear();
				return origOffset;
			}
			int len = plaintext[offset] & 0xFF;
			offset++;
			if(len > plaintext.length - offset) {
				packet.lossyMessages.clear();
				return origOffset;
			}
			byte[] fragment = Arrays.copyOfRange(plaintext, offset, offset + len);
			packet.lossyMessages.add(fragment);
			offset += len;
			if(offset == plaintext.length) return offset;
		}
	}

	public int toBytes(byte[] buf, int offset, Random paddingGen) {
	    int origOffset = offset;
		buf[offset] = (byte) (sequenceNumber >>> 24);
		buf[offset + 1] = (byte) (sequenceNumber >>> 16);
		buf[offset + 2] = (byte) (sequenceNumber >>> 8);
		buf[offset + 3] = (byte) (sequenceNumber);
		offset += 4;

		//Add acks

			buf[offset++] = (byte) (ackRangeCount);
			Iterator<Integer> acksIterator = acks.iterator();
			if(acksIterator.hasNext()) {
				int startRange = 0, endRange = -1;
				int nextAck = acksIterator.next();
				for (int i = 0; acksIterator.hasNext(); i++) {
				    assert(nextAck - endRange >= 0);
					if (i == 0 || (nextAck - endRange >= 254)) {
					    if(i != 0)
					        buf[offset++] = (byte) 0; // Mark a far offset
						buf[offset] = (byte) (nextAck >>> 24);
						buf[offset + 1] = (byte) (nextAck >>> 16);
						buf[offset + 2] = (byte) (nextAck >>> 8);
						buf[offset + 3] = (byte) (nextAck);
						offset += 4;
					} else {
						assert(nextAck - endRange < 254);
						buf[offset++] = (byte) (nextAck - endRange);
					}
					
					endRange = startRange = nextAck;
					
					while(acksIterator.hasNext() &&  ((nextAck = acksIterator.next()) - endRange == 1) &&  (endRange - startRange < 254)) {
						endRange++;
					}
					
					byte rangeSize = (byte) (endRange - startRange + 1);
					buf[offset++] = rangeSize;
					
					// TODO: Add zero-cost dub-acks if any
				}
				if (nextAck != endRange) { // Edge-case when the last ack does not fit into previous range
                    assert(nextAck - endRange >= 0);
					if (nextAck - endRange >= 254 && endRange != -1) {
						buf[offset++] = (byte) 0; // Mark a far offset
					}
					if (ackRangeCount == 1 || (nextAck - endRange >= 254)) {
						buf[offset] = (byte) (nextAck >>> 24);
						buf[offset + 1] = (byte) (nextAck >>> 16);
						buf[offset + 2] = (byte) (nextAck >>> 8);
						buf[offset + 3] = (byte) (nextAck);
						offset += 4;
						buf[offset++] = (byte) 1;
					} else {
						buf[offset++] = (byte) (nextAck - endRange);
						buf[offset++] = (byte) 1;
					}
				}
			}
		
		//Add fragments
		int prevFragmentID = -1;
		for(MessageFragment fragment : fragments) {
			if(fragment.shortMessage) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x80);
			if(fragment.isFragmented) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x40);
			if(fragment.firstFragment) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x20);

			if(prevFragmentID == -1 || (fragment.messageID - prevFragmentID >= 4096)) {
				buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x10);
				buf[offset] = (byte) ((buf[offset] & 0xFF) | ((fragment.messageID >>> 24) & 0x0F));
				buf[offset + 1] = (byte) (fragment.messageID >>> 16);
				buf[offset + 2] = (byte) (fragment.messageID >>> 8);
				buf[offset + 3] = (byte) (fragment.messageID);
				offset += 4;
			} else {
				int compressedMsgID = fragment.messageID - prevFragmentID;
				buf[offset] = (byte) ((buf[offset] & 0xFF) | ((compressedMsgID >>> 8) & 0x0F));
				buf[offset + 1] = (byte) (compressedMsgID);
				offset += 2;
			}
			prevFragmentID = fragment.messageID;

			if(fragment.shortMessage) {
				buf[offset++] = (byte) (fragment.fragmentLength);
			} else {
				buf[offset] = (byte) (fragment.fragmentLength >>> 8);
				buf[offset + 1] = (byte) (fragment.fragmentLength);
				offset += 2;
			}

			if(fragment.isFragmented) {
				// If firstFragment is true, add total message length. Else, add fragment offset
				int value = fragment.firstFragment ? fragment.messageLength : fragment.fragmentOffset;

				if(fragment.shortMessage) {
					buf[offset++] = (byte) (value);
				} else {
					buf[offset] = (byte) (value >>> 8);
					buf[offset + 1] = (byte) (value);
					offset += 2;
				}
			}

			System.arraycopy(fragment.fragmentData, 0, buf, offset, fragment.fragmentLength);
			offset += fragment.fragmentLength;
		}
		
		if(!lossyMessages.isEmpty()) {
			for(byte[] msg : lossyMessages) {
				buf[offset++] = 0x1F;
				assert(msg.length <= 255);
				buf[offset++] = (byte) msg.length;
				System.arraycopy(msg, 0, buf, offset, msg.length);
				offset += msg.length;
			}
		}

        assert(offset - origOffset == length);

		if(offset < buf.length) {
			//More room, so add padding
			Util.randomBytes(paddingGen, buf, offset, buf.length - offset);

			byte b = (byte) (buf[offset] & 0x9F); //Make sure firstFragment and isFragmented isn't set
			if(b == 0x1F)
				b = (byte)0x9F; // Make sure it doesn't match the pattern for lossy messages
			buf[offset] = b;
		}
		
		return offset;
	}

	public boolean addAck(int ack, int maxPacketSize) {
		if(ack < 0) throw new IllegalArgumentException("Got negative ack: " + ack);
		if(acks.contains(ack)) return true;
		
			acks.add(ack);
			int nearRangeCount = 0, farRangeCount = 0;
	
			Iterator<Integer> acksIterator = acks.iterator();
			int startRange = 0, endRange = -1;
			int nextAck = acksIterator.next();
			while (acksIterator.hasNext()) {
				if (nextAck - endRange > 254 && endRange != -1) {
					farRangeCount++;
				} else {
					nearRangeCount++;
				}
				endRange = startRange = nextAck;
				while(acksIterator.hasNext() && ((nextAck = acksIterator.next()) - endRange == 1) && (endRange - startRange < 254)) {
					endRange++;
				}
				// TODO: Add zero-cost dub-acks if any
			}
			if (nextAck != endRange) {
				if (nextAck - endRange < 254 || endRange == -1) {
					nearRangeCount++;
				} else {
					farRangeCount++;
				}
			}
			if (nearRangeCount + farRangeCount > 254) {
				acks.remove(ack);
				return false;
			}
			//              (start + offset) + (rangeCount-1)    *(1byte deltaFromPrevios + length) + farRangeCount*(flag + 4byte packetSequenceNumber + length)
			int blockSize = 5                + (nearRangeCount-1)*2                                 + farRangeCount*6;
			int finalLength = length + blockSize - ackBlockByteSize;
			if(finalLength > maxPacketSize) {
			    acks.remove(ack);
			    return false;
			}
			length = finalLength;
			ackBlockByteSize = blockSize;
			ackRangeCount = farRangeCount + nearRangeCount;

		return true;
	}

	private int oldMsgIDLength;
	public int addMessageFragment(MessageFragment frag) {
		length += frag.length();
		fragments.add(frag);
		Collections.sort(fragments, new MessageFragmentComparator());

		int msgIDLength = 0;
		int prevMessageID = -1;
		for(MessageFragment fragment : fragments) {
			if((prevMessageID == -1) || (fragment.messageID - prevMessageID >= 4096)) {
				msgIDLength += 2;
			}
			prevMessageID = fragment.messageID;
		}

		length += (msgIDLength - oldMsgIDLength);
		oldMsgIDLength = msgIDLength;

		return length;
	}
	
	public int addLossyMessage(byte[] buf) {
		if(buf.length > 255) throw new IllegalArgumentException();
		lossyMessages.add(buf);
		length += buf.length + 2;
		return length;
	}
	
	public boolean addLossyMessage(byte[] buf, int maxPacketSize) {
		if(length + buf.length + 2 > maxPacketSize) return false;
		if(buf.length > 255) throw new IllegalArgumentException();
		lossyMessages.add(buf);
		length += buf.length + 2;
		return true;
	}
	
	public void removeLossyMessage(byte[] buf) {
		if(lossyMessages.remove(buf)) {
			length -= buf.length + 2;
		}
	}
	/** Get the list of lossy messages. Note that for early versions these may be bogus,
	 * so be careful when parsing them. Note also that these must be processed before the
	 * rest of the messages on the packet. */
	public List<byte[]> getLossyMessages() {
		return lossyMessages;
	}

	public boolean getError() {
		return error;
        }

	public List<MessageFragment> getFragments() {
		return fragments;
        }

	public int getSequenceNumber() {
		return sequenceNumber;
        }

	public void setSequenceNumber(int sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	public SortedSet<Integer> getAcks() {
		return acks;
        }

	public int getLength() {
		return length;
	}

	@Override
	public String toString() {
		return "Packet " + sequenceNumber + ": " + length + " bytes, " + acks.size() + " acks, " + fragments.size() + " fragments";
	}

	private static class MessageFragmentComparator implements Comparator<MessageFragment> {
		@Override
		public int compare(MessageFragment frag1, MessageFragment frag2) {
			if(frag1.messageID < frag2.messageID) return -1;
			if(frag1.messageID == frag2.messageID) return 0;
			return 1;
		}
	}

	public void onSent(int totalPacketLength, BasePeerNode pn) {
		int totalMessageData = 0;
		int size = fragments.size();
		int biggest = 0;
		for(MessageFragment frag: fragments) {
			totalMessageData += frag.fragmentLength;
			size++;
			if(biggest < frag.messageLength) biggest = frag.messageLength;
		}
		int overhead = totalPacketLength - totalMessageData;
		if(logDEBUG) Logger.debug(this, "Total packet overhead: "+overhead+" for "+size+" messages total message length "+totalMessageData+" total packet length "+totalPacketLength+" biggest message "+biggest);
		for(MessageFragment frag: fragments) {
			// frag.wrapper is always non-null on sending.
			frag.wrapper.onSent(frag.fragmentOffset, frag.fragmentOffset + frag.fragmentLength - 1, overhead / size, pn);
		}			
	}
	
	String fragmentsAsString() {
		return Arrays.toString(fragments.toArray());
	}

	public int countAcks() {
		return acks.size();
	}

	/**
	 * @return True if there are no MessageFragment's to send.
	 */
	public boolean noFragments() {
		return fragments.isEmpty();
	}
}
