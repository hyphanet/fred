package freenet.node;

import java.util.Iterator;
import java.util.LinkedList;

import freenet.support.Logger;
import freenet.support.LogThresholdCallback;

class NPFPacket {
	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(Logger.MINOR, this);
			}
		});
	}

	private long sequenceNumber;
	private final LinkedList<Long> acks = new LinkedList<Long>();
	private final LinkedList<MessageFragment> fragments = new LinkedList<MessageFragment>();
	private boolean error;
	private int length = 5; //Sequence number (4), numAcks(1)

	public static NPFPacket create(byte[] plaintext) {
		NPFPacket packet = new NPFPacket();
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
		int numAcks = plaintext[offset++] & 0xFF;
		if(plaintext.length < (offset + numAcks + (numAcks > 0 ? 3 : 0))) {
			packet.error = true;
			return packet;
		}

		long firstAck = 0;
		for(int i = 0; i < numAcks; i++) {
			long ack = 0;
			if(i == 0) {
				firstAck = ((plaintext[offset] & 0xFF) << 24)
				                | ((plaintext[offset + 1] & 0xFF) << 16)
				                | ((plaintext[offset + 2] & 0xFF) << 8)
				                | (plaintext[offset + 3] & 0xFF);
				ack = firstAck;
				offset += 4;
			} else {
				ack = firstAck + (plaintext[offset++] & 0xFF);
			}
			packet.acks.add(ack);
		}

		//Handle received message fragments
		while(offset < plaintext.length) {
			if(plaintext.length < (offset + 2)) {
				packet.error = true;
				return packet;
			}

			boolean shortMessage = (plaintext[offset] & 0x80) != 0;
			boolean isFragmented = (plaintext[offset] & 0x40) != 0;
			boolean firstFragment = (plaintext[offset] & 0x20) != 0;
			int messageID = ((plaintext[offset] & 0x1F) << 8)
			                | (plaintext[offset + 1] & 0xFF);
			offset += 2;

			if(!isFragmented && !firstFragment) {
				Logger.warning(NPFPacket.class, "Received unfragmented message, but the fragment wasn't"
				                + " the first");
				packet.error = true;
				break;
			}

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
			if(fragmentLength < 0) {
				//Probably means that offset is wrong, so continuing isn't a good idea
				Logger.warning(NPFPacket.class, "Read negative fragment length from offset "
				                + (shortMessage ? offset - 1 : offset - 2)
						+ ". Probably a bug");
				offset = -1;
				packet.error = true;
				break;
			}

			int messageLength = -1;
			int fragmentOffset = 0;
			if(isFragmented) {
				int value;
				if(shortMessage) {
					value = plaintext[offset++] & 0xFF;
				} else {
					value = ((plaintext[offset] & 0xFF) << 16)
					                | ((plaintext[offset + 1] & 0xFF) << 8)
							| (plaintext[offset + 2] & 0xFF);
					offset += 3;
				}
				if(value < 0) {
					Logger.warning(NPFPacket.class, "Read negative value from offset "
					                + (shortMessage ? offset - 1 : offset - 3)
							+ ". Probably a bug");
					packet.error = true;
				}
				if(firstFragment) messageLength = value;
				else fragmentOffset = value;

				if(messageLength == fragmentLength) {
					Logger.warning(NPFPacket.class, "Received fragmented message, but fragment contains the entire message");
				}
			} else {
				messageLength = fragmentLength;
			}
			byte[] fragmentData = new byte[fragmentLength];
			if((offset + fragmentLength) > plaintext.length) {
				if(logMINOR) Logger.minor(NPFPacket.class, "Fragment doesn't fit in the received packet");
				packet.error = true;
				break;
			}
			System.arraycopy(plaintext, offset, fragmentData, 0, fragmentLength);
			offset += fragmentLength;

			packet.fragments.add(new MessageFragment(shortMessage, isFragmented, firstFragment,
			                messageID, fragmentLength, messageLength, fragmentOffset, fragmentData));
		}

		return packet;
	}

	public int toBytes(byte[] buf, int offset) {
		buf[offset] = (byte) (sequenceNumber >>> 24);
		buf[offset + 1] = (byte) (sequenceNumber >>> 16);
		buf[offset + 2] = (byte) (sequenceNumber >>> 8);
		buf[offset + 3] = (byte) (sequenceNumber);
		offset += 4;

		//Add acks
		buf[offset++] = (byte) (acks.size());
		long firstAck;
		Iterator<Long> acksIterator = acks.iterator();
		if(acksIterator.hasNext()) {
			firstAck = acksIterator.next();
			buf[offset] = (byte) (firstAck >>> 24);
			buf[offset + 1] = (byte) (firstAck >>> 16);
			buf[offset + 2] = (byte) (firstAck >>> 8);
			buf[offset + 3] = (byte) (firstAck);
			offset += 4;

			while(acksIterator.hasNext()) {
				long ack = acksIterator.next();
				buf[offset++] = (byte) (ack - firstAck);
			}
		}

		//Add fragments
		for(MessageFragment fragment : fragments) {
			buf[offset] = (byte) ((fragment.messageID >>> 8) & 0x1F);
			buf[offset + 1] = (byte) (fragment.messageID);
			if(fragment.shortMessage) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x80);
			if(fragment.isFragmented) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x40);
			if(fragment.firstFragment) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x20);
			offset += 2;

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
					buf[offset] = (byte) (value >>> 16);
					buf[offset + 1] = (byte) (value >>> 8);
					buf[offset + 2] = (byte) (value);
					offset += 3;
				}
			}

			System.arraycopy(fragment.fragmentData, 0, buf, offset, fragment.fragmentLength);
			offset += fragment.fragmentLength;
		}

		return offset;
	}

	public int addAck(long ack) {
		if(acks.size() == 0) {
			length += 4;
		} else {
			length++;
		}
		acks.add(ack);
		return length;
	}

	public int addMessageFragment(MessageFragment frag) {
		fragments.add(frag);
		length += frag.length();
		return length;
	}

	public boolean getError() {
		return error;
        }

	public LinkedList<MessageFragment> getFragments() {
		return fragments;
        }

	public long getSequenceNumber() {
		return sequenceNumber;
        }

	public void setSequenceNumber(long sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	public LinkedList<Long> getAcks() {
		return acks;
        }

	public int getLength() {
		return length;
	}
}
