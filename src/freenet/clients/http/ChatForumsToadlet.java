package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class ChatForumsToadlet extends Toadlet implements LinkEnabledCallback {

	private final PluginManager plugins;
	
	protected ChatForumsToadlet(HighLevelSimpleClient client, PluginManager plugins) {
		super(client);
		this.plugins = plugins;
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(ctx.getAlertManager().createSummary());
		
		HTMLNode contentBox = ctx.getPageMaker().getInfobox("infobox-information", l10n("title"), contentNode, "chat-list", true);

		NodeL10n.getBase().addL10nSubstitution(contentBox.addChild("p"), "ChatForumsToadlet.fsng",
				new String[] { "fsng" },
				new HTMLNode[] { HTMLNode.link("/USK@t5zaONbYd5DvGNNSokVnDCdrIEytn9U5SSD~pYF0RTE,guWyS9aCMcywU5PFBrKsMiXs7LzwKfQlGSRi17fpffc,AQACAAE/fsng/-56/") });

		
		HTMLNode ul = contentBox.addChild("ul");
		HTMLNode li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.fms",
		        new String[] { "fms", "fms-help" },
		        new HTMLNode[] { HTMLNode.link("/USK@0npnMrqZNKRCRoGojZV93UNHCMN-6UU3rRSAmP6jNLE,~BG-edFtdCC1cSH4O3BWdeIYa8Sw5DfyrSV-TKdO5ec,AQACAAE/fms/-137/"),
		                HTMLNode.link("/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/fms.htm")});
		li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.sone",
		       new String[] { "sone"},
			       new HTMLNode[] {
				   HTMLNode.link("/USK@nwa8lHa271k2QvJ8aa0Ov7IHAV-DFOCFgmDt3X6BpCI,DuQSUZiI~agF8c-6tjsFFGuZ8eICrzWCILB60nT8KKo,AQACAAE/sone/-72/")});
		contentBox.addChild("p", l10n("content2"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private static String l10n(String string) {
		return NodeL10n.getBase().getString("ChatForumsToadlet." + string);
	}

	@Override
	public String path() {
		return "/chat/";
	}

	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return !plugins.isPluginLoaded("plugins.Freetalk.Freetalk");
	}

	
}
