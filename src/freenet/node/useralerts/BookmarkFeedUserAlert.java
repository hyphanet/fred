package freenet.node.useralerts;

import freenet.keys.FreenetURI;
import freenet.node.DarknetPeerNode;
import freenet.node.fcp.ReceivedBookmarkFeed;
import freenet.support.HTMLNode;

public class BookmarkFeedUserAlert extends AbstractUserAlert {
	private final DarknetPeerNode sourcePeerNode;
	private final FreenetURI uri;
	private final String sourceNodeName;
	private final String targetNodeName;
	private final int fileNumber;
	private final String name;
	private final String description;
	private final boolean hasAnActivelink;
	private final long composed;
	private final long sent;
	private final long received;

	public BookmarkFeedUserAlert(DarknetPeerNode sourcePeerNode, String sourceNodeName, String targetNodeName,
			String name, String description, boolean hasAnActivelink, int fileNumber, FreenetURI uri,
			long composed, long sent, long received) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.sourcePeerNode = sourcePeerNode;
		this.name = name;
		this.description = description;
		this.uri = uri;
		this.sourceNodeName = sourceNodeName;
		this.targetNodeName = targetNodeName;
		this.fileNumber = fileNumber;
		this.hasAnActivelink = hasAnActivelink;
		this.composed = composed;
		this.sent = sent;
		this.received = received;
	}

	@Override
	public String getTitle() {
		return sourceNodeName + " recommends you a freesite";
	}

	@Override
	public String getText() {
		return "Name: " + name + "\nURI: " + uri + "\nDescription: " + description;
	}

	@Override
	public String getShortText() {
		return sourceNodeName + " recommends you a freesite";
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("a", "href",
				"/?newbookmark=" + uri + "&desc=" + name + "&hasAnActivelink=" + hasAnActivelink)
				.addChild(
						"img",
						new String[] { "src", "alt", "title" },
						new String[] { "/static/icon/bookmark-new.png", "Add as a bookmark",
								"Add as a bookmark" });
		alertNode.addChild("a", "href", uri.toString()).addChild("#", name);
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
	public ReceivedBookmarkFeed getFCPMessage(String identifier) {
		return new ReceivedBookmarkFeed(identifier, getTitle(), getShortText(), getText(), sourceNodeName,
				targetNodeName, composed, sent, received, name, uri, description, hasAnActivelink);
	}
}
