package freenet.node;

import java.util.LinkedList;

import freenet.support.Logger;

class ReceivedPacket {
	private long sequenceNumber;
	private final LinkedList<Long> acks = new LinkedList<Long>();
	private final LinkedList<MessageFragment> fragments = new LinkedList<MessageFragment>();
	private boolean error;

	private ReceivedPacket() {

	}

	public static ReceivedPacket create(byte[] plaintext) {
		ReceivedPacket packet = new ReceivedPacket();
		int offset = 0;

		packet.sequenceNumber = ((plaintext[offset] & 0xFF) << 24)
		                | ((plaintext[offset] & 0xFF) << 16)
		                | ((plaintext[offset] & 0xFF) << 8)
		                | (plaintext[offset] & 0xFF);
		offset += 4;

		//Process received acks
		int numAcks = plaintext[offset++] & 0xFF;
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
			boolean shortMessage = (plaintext[offset] & 0x80) != 0;
			boolean isFragmented = (plaintext[offset] & 0x40) != 0;
			boolean firstFragment = (plaintext[offset] & 0x20) != 0;
			int messageID = ((plaintext[offset] & 0x1F) << 8)
			                | (plaintext[offset + 1] & 0xFF);
			offset += 4;

			int fragmentLength;
			if(shortMessage) {
				fragmentLength = plaintext[offset++];
			} else {
				fragmentLength = ((plaintext[offset] & 0xFF) << 8)
				                | (plaintext[offset + 1] & 0xFF);
				offset += 2;
			}
			if(fragmentLength < 0) {
				//Probably means that offset is wrong, so continuing isn't a good idea
				Logger.warning(ReceivedPacket.class, "Read negative fragment length from offset "
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
					Logger.warning(ReceivedPacket.class, "Read negative value from offset "
					                + (shortMessage ? offset - 1 : offset - 3)
							+ ". Probably a bug");
					packet.error = true;
				}
				if(firstFragment) messageLength = value;
				else fragmentOffset = value;
			}
			byte[] fragmentData = new byte[fragmentLength];
			System.arraycopy(plaintext, offset, fragmentData, 0, fragmentLength);

			packet.fragments.add(new MessageFragment(shortMessage, isFragmented, firstFragment,
			                messageID, fragmentLength, messageLength, fragmentOffset, fragmentData));
		}

		return packet;
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

	public LinkedList<Long> getAcks() {
		return acks;
        }
}