package freenet.node.useralerts;

import java.lang.ref.WeakReference;

import freenet.clients.fcp.BookmarkFeed;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.PeerNode;
import freenet.support.HTMLNode;

public class BookmarkFeedUserAlert extends AbstractUserAlert {
	private final WeakReference<PeerNode> peerRef;
	private final FreenetURI uri;
	private final int fileNumber;
	private final String name;
	private final String description;
	private final boolean hasAnActivelink;
	private final long composed;
	private final long sent;
	private final long received;
	private String sourceNodeName;

	public BookmarkFeedUserAlert(DarknetPeerNode sourcePeerNode,
			String name, String description, boolean hasAnActivelink, int fileNumber, FreenetURI uri,
			long composed, long sent, long received) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.name = name;
		this.description = description;
		this.uri = uri;
		this.fileNumber = fileNumber;
		this.hasAnActivelink = hasAnActivelink;
		this.composed = composed;
		this.sent = sent;
		this.received = received;
		peerRef = sourcePeerNode.getWeakRef();
		sourceNodeName = sourcePeerNode.getName();
	}

	@Override
	public String getTitle() {
		return l10n("title", "from", sourceNodeName);
	}

	@Override
	public String getText() {
		StringBuilder sb = new StringBuilder();
		sb.append(l10n("peerName")).append(" ").append(name).append("\n");
		sb.append(l10n("bookmarkURI")).append(" ").append(uri).append("\n");
		if(description != null && description.length() != 0)
			sb.append(l10n("bookmarkDescription")).append(" ").append(description);
		return sb.toString();
	}

	@Override
	public String getShortText() {
		return getTitle();
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("a", "href",
				"/?newbookmark=" + uri + "&desc=" + name + "&hasAnActivelink=" + hasAnActivelink)
				.addChild(
						"img",
						new String[] { "src", "alt", "title" },
						new String[] { "/static/icon/bookmark-new.png", l10n("addAsABookmark"),
								l10n("addAsABookmark") });
		alertNode.addChild("a", "href", "/freenet:" + uri.toString()).addChild("#", name);
		if (description != null && description.length() != 0) {
			String[] lines = description.split("\n");
			alertNode.addChild("br");
			alertNode.addChild("br");
			alertNode.addChild("#", l10n("bookmarkDescription"));
			alertNode.addChild("br");
			for (int i = 0; i < lines.length; i++) {
				alertNode.addChild("#", lines[i]);
				if (i != lines.length - 1)
					alertNode.addChild("br");
			}
		}
		return alertNode;
	}

	@Override
	public String dismissButtonText() {
		return l10n("delete");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("BookmarkFeedUserAlert." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("BookmarkFeedUserAlert." + key, pattern, value);
	}

	@Override
	public void onDismiss() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			pn.deleteExtraPeerDataFile(fileNumber);
	}

	@Override
	public BookmarkFeed getFCPMessage() {
		return new BookmarkFeed(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime(), sourceNodeName, composed, sent, received, name, uri, description, hasAnActivelink);
	}

	@Override
	public boolean isValid() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			sourceNodeName = pn.getName();
		return true;
	}
}
