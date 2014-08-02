package freenet.node.useralerts;

import java.lang.ref.WeakReference;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.URIFeedMessage;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.PeerNode;
import freenet.support.HTMLNode;

public class DownloadFeedUserAlert extends AbstractUserAlert {
	private final WeakReference<PeerNode> peerRef;
	private final FreenetURI uri;
	private final int fileNumber;
	private final String description;
	private final long composed;
	private final long sent;
	private final long received;
	private String sourceNodeName;

	public DownloadFeedUserAlert(DarknetPeerNode sourcePeerNode, 
			String description, int fileNumber, FreenetURI uri, long composed, long sent, long received) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.description = description;
		this.uri = uri;
		this.fileNumber = fileNumber;
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
		sb.append(l10n("fileURI")).append(" ").append(uri).append("\n");
		if(description != null && description.length() != 0)
			sb.append(l10n("fileDescription")).append(" ").append(description);
		return sb.toString();
	}

	@Override
	public String getShortText() {
		return getTitle();
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("a", "href", "/" + uri).addChild("#", uri.toShortString());
		if (description != null && description.length() != 0) {
			String[] lines = description.split("\n");
			alertNode.addChild("br");
			alertNode.addChild("br");
			alertNode.addChild("#", l10n("fileDescription"));
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
		return NodeL10n.getBase().getString("DownloadFeedUserAlert." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("DownloadFeedUserAlert." + key, pattern, value);
	}

	@Override
	public void onDismiss() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			pn.deleteExtraPeerDataFile(fileNumber);
	}

	@Override
	public FCPMessage getFCPMessage() {
		return new URIFeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime(),
				sourceNodeName, composed, sent, received, uri, description);
	}

	@Override
	public boolean isValid() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null)
			sourceNodeName = pn.getName();
		return true;
	}
}
