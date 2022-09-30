/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.useralerts;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.TextFeedMessage;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.PeerNode;
import freenet.support.HTMLNode;

// Node To Node Text Message User Alert
public class N2NTMUserAlert extends AbstractUserAlert {
	private final WeakReference<PeerNode> peerRef;
	private final String messageText;
	private final int fileNumber;
	private final long composedTime;
	private final long sentTime;
	private final long receivedTime;
	private final long msgid;
	private String sourceNodeName;
	private String sourcePeer;

	public N2NTMUserAlert(DarknetPeerNode sourcePeerNode, String message, int fileNumber, long composedTime,
			long sentTime, long receivedTime, long msgid) {
		super(true, null, null, null, null, UserAlert.MINOR, true, null, true, null);
		this.messageText = message;
		this.fileNumber = fileNumber;
		this.composedTime = composedTime;
		this.sentTime = sentTime;
		this.receivedTime = receivedTime;
		this.peerRef = sourcePeerNode.getWeakRef();
		this.sourceNodeName = sourcePeerNode.getName();
		this.sourcePeer = sourcePeerNode.getPeer().toString();
		this.msgid = msgid;
	}

	public N2NTMUserAlert(DarknetPeerNode sourcePeerNode, String message, int fileNumber, long composedTime,
			long sentTime, long receivedTime) {
                this(sourcePeerNode, message, fileNumber, composedTime, sentTime, receivedTime, -1);
	}

	@Override
	public String getTitle() {
		return l10n("title", new String[] { "number", "peername", "peer" },
				new String[] { Integer.toString(fileNumber), sourceNodeName, sourcePeer });
	}

	@Override
	public String getText() {
		return l10n("header", new String[] { "from", "composed", "sent", "received" },
				new String[] { sourceNodeName, DateFormat.getInstance().format(new Date(composedTime)),
						DateFormat.getInstance().format(new Date(sentTime)),
						DateFormat.getInstance().format(new Date(receivedTime)) })
				+ ": " + messageText;
	}

	@Override
	public String getShortText() {
		return l10n("headerShort", "from", sourceNodeName);
	}

	@Override
	public HTMLNode getHTMLText() {
		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("p",
				l10n("header", new String[] { "from", "composed", "sent", "received" },
						new String[] { sourceNodeName, DateFormat.getInstance().format(new Date(composedTime)),
								DateFormat.getInstance().format(new Date(sentTime)),
								DateFormat.getInstance().format(new Date(receivedTime)) }));
		String[] lines = messageText.split("\n");
		for (int i = 0, c = lines.length; i < c; i++) {
			alertNode.addChild("#", lines[i]);
			if (i != lines.length - 1)
				alertNode.addChild("br");
		}

		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if (pn != null)
			alertNode.addChild("p").addChild("a", "href", "/send_n2ntm/?peernode_hashcode=" + pn.hashCode(),
					l10n("reply"));
		return alertNode;
	}

	@Override
	public String dismissButtonText() {
		return l10n("delete");
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key);
	}

	private String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key, patterns, values);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("N2NTMUserAlert." + key, pattern, value);
	}

	@Override
	public void onDismiss() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if (pn != null)
			pn.deleteExtraPeerDataFile(fileNumber);
	}

	@Override
	public FCPMessage getFCPMessage() {
		return new TextFeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime(),
				sourceNodeName, composedTime, sentTime, receivedTime, messageText);
	}

	@Override
	public long getUpdatedTime() {
		return receivedTime;
	}

	public String getMessageText() {
		return messageText;
	}

	public int getFileNumber() {
		return fileNumber;
	}

	public long getComposedTime() {
		return composedTime;
	}

	public long getSentTime() {
		return sentTime;
	}

	public long getMsgid() {
		return msgid;
	}

	@Override
	public boolean isValid() {
		DarknetPeerNode pn = (DarknetPeerNode) peerRef.get();
		if(pn != null) {
			sourceNodeName = pn.getName();
			sourcePeer = pn.getPeer().toString();
		}
		return true;
	}

}
