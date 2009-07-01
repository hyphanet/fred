package freenet.client.async;

import freenet.node.fcp.FCPMessage;

public interface FeedCallback {
	public String getIdentifier();
	public void sendReply(FCPMessage message);
}
