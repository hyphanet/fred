package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.useralerts.UserAlertManager;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class ChatForumsToadlet extends Toadlet implements LinkEnabledCallback {

	private final UserAlertManager alerts;
	private final PluginManager plugins;
	private final Node node;
	
	protected ChatForumsToadlet(HighLevelSimpleClient client, UserAlertManager alerts, PluginManager plugins, Node node) {
		super(client);
		this.alerts = alerts;
		this.plugins = plugins;
		this.node = node;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(alerts.createSummary());
		
		HTMLNode contentBox = ctx.getPageMaker().getInfobox("infobox-information", l10n("title"), contentNode, "chat-list", true);
		
		contentBox.addChild("p", l10n("content1"));
		HTMLNode ul = contentBox.addChild("ul");
		HTMLNode li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.frost", new String[] { "frost-freenet", "frost-web", "frost-help" },
				new HTMLNode[] { HTMLNode.link("/freenet:USK@QRZAI1nSm~dAY2hTdzVWXmEhkaI~dso0OadnppBR7kE,wq5rHGBI7kpChBe4yRmgBChIGDug7Xa5SG9vYGXdxR0,AQACAAE/frost/9/"), HTMLNode.link("/?_CHECKED_HTTP_=http://jtcfrost.sourceforge.net/"), HTMLNode.link("/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/frost.htm")});
		li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.fms", new String[] { "fms", "fms-help" }, 
				new HTMLNode[] { HTMLNode.link("/USK@0npnMrqZNKRCRoGojZV93UNHCMN-6UU3rRSAmP6jNLE,~BG-edFtdCC1cSH4O3BWdeIYa8Sw5DfyrSV-TKdO5ec,AQACAAE/fms/101/"), HTMLNode.link("/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/fms.htm")});
		contentBox.addChild("p", l10n("content2"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private static final String l10n(String string) {
		return NodeL10n.getBase().getString("ChatForumsToadlet." + string);
	}

	@Override
	public String path() {
		return "/chat/";
	}

	public boolean isEnabled(ToadletContext ctx) {
		return !plugins.isPluginLoaded("plugins.Freetalk.Freetalk");
	}

	
}
