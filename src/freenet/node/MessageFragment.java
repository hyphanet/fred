package freenet.node;

class MessageFragment {
	boolean shortMessage;
	boolean isFragmented;
	boolean firstFragment;
	int messageID;
	int fragmentLength;
	int messageLength;
	int fragmentOffset;
	byte[] fragmentData;
	MessageWrapper wrapper;

	public MessageFragment(boolean shortMessage, boolean isFragmented, boolean firstFragment, int messageID,
	                int fragmentLength, int messageLength, int fragmentOffset, byte[] fragmentData,
	                MessageWrapper wrapper) {
		this.shortMessage = shortMessage;
		this.isFragmented = isFragmented;
		this.firstFragment = firstFragment;
		this.messageID = messageID;
		this.fragmentLength = fragmentLength;
		this.messageLength = messageLength;
		this.fragmentOffset = fragmentOffset;
		this.fragmentData = fragmentData;
		this.wrapper = wrapper;
	}

	public int length() {
		return 2 //Message id + flags
		                + (shortMessage ? 1 : 2) //Fragment length
		                + (isFragmented ? (shortMessage ? 1 : 3) : 0) //Fragment offset or message length
		                + fragmentData.length;

        }

	public String toString() {
		return "Fragment from message " + messageID + ": offset " + fragmentOffset + ", data length " + fragmentData.length;
	}
}
