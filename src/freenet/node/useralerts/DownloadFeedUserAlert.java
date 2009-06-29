package freenet.node.useralerts;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.node.fcp.FCPMessage;
import freenet.node.fcp.ReceivedDownloadFeedMessage;
import freenet.support.HTMLNode;

public class DownloadFeedUserAlert extends AbstractUserAlert {
	private final DarknetPeerNode sourcePeerNode;
	private final FreenetURI URI;
	private final String sourceNodeName;
	private final String targetNodeName;
	private final int fileNumber;
	private final String description;
	private final long composed;
	private final long sent;
	private final long received;

	public DownloadFeedUserAlert(DarknetPeerNode sourcePeerNode, String sourceNodeName, String targetNodeName,
			String description, int fileNumber, FreenetURI URI, long composed, long sent, long received) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.sourcePeerNode = sourcePeerNode;
		this.description = description;
		this.URI = URI;
		this.sourceNodeName = sourceNodeName;
		this.targetNodeName = targetNodeName;
		this.fileNumber = fileNumber;
		this.composed = composed;
		this.sent = sent;
		this.received = received;
	}

	@Override
	public String getTitle() {
		return sourceNodeName + " recommends you a file";
	}

	@Override
	public String getText() {
		return "\nURI: " + URI + "\nDescription: " + description;
	}

	@Override
	public String getShortText() {
		return sourceNodeName + " recommends you a file";
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("a", "href", "/" + URI).addChild("#", URI.toShortString());
		if (description != null && !description.isEmpty()) {
			alertNode.addChild("br");
			alertNode.addChild("br");
			alertNode.addChild("#", "Description:");
			alertNode.addChild("br");
			alertNode.addChild("#", description);
		}
		return alertNode;
	}

	@Override
	public String dismissButtonText() {
		return "Delete";
	}

	@Override
	public void onDismiss() {
		sourcePeerNode.deleteExtraPeerDataFile(fileNumber);
	}

	@Override
	public FCPMessage getFCPMessage(String identifier) {
		return new ReceivedDownloadFeedMessage(identifier, getTitle(), getShortText(), getText(),
				sourceNodeName, targetNodeName, composed, sent, received, URI, description);
	}
}
