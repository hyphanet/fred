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

	public MessageFragment(boolean shortMessage, boolean isFragmented, boolean firstFragment, int messageID,
	                int fragmentLength, int messageLength, int fragmentOffset, byte[] fragmentData) {
		this.shortMessage = shortMessage;
		this.isFragmented = isFragmented;
		this.firstFragment = firstFragment;
		this.messageID = messageID;
		this.fragmentLength = fragmentLength;
		this.messageLength = messageLength;
		this.fragmentOffset = fragmentOffset;
		this.fragmentData = fragmentData;
	}
}
