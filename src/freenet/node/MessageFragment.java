/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

class MessageFragment {
	final boolean shortMessage;
	final boolean isFragmented;
	final boolean firstFragment;
	final int messageID;
	final int fragmentLength;
	final int messageLength;
	final int fragmentOffset;
	final byte[] fragmentData;
	final MessageWrapper wrapper;

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
		                + (isFragmented ? (shortMessage ? 1 : 2) : 0) //Fragment offset or message length
		                + fragmentData.length;

        }

	@Override
	public String toString() {
		return "Fragment from message " + messageID + ": offset " + fragmentOffset + ", data length " + fragmentData.length;
	}
}
