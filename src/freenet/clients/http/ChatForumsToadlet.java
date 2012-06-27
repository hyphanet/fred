package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.useralerts.UserAlertManager;
import freenet.pluginmanager.PluginManager;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;

import java.io.IOException;
import java.net.URI;

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
		
		contentBox.addChild("p", l10n("freetalkRecommended"));
		contentBox.addChild("p", l10n("freetalkCaveat"));
		ctx.addFormChild(contentBox, path(), "loadFreetalkButton").addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "loadFreetalk", l10n("freetalkButton") });
		contentBox.addChild("p", l10n("othersIntro"));
		
		HTMLNode ul = contentBox.addChild("ul");
		HTMLNode li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.fms",
		        new String[] { "fms", "fms-help" },
		        new HTMLNode[] { HTMLNode.link("/USK@0npnMrqZNKRCRoGojZV93UNHCMN-6UU3rRSAmP6jNLE,~BG-edFtdCC1cSH4O3BWdeIYa8Sw5DfyrSV-TKdO5ec,AQACAAE/fms/127/"),
		                HTMLNode.link("/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/fms.htm")});
		li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.frost",
		        new String[] { "frost-freenet", "frost-web", "frost-help" },
		                new HTMLNode[] {
			                HTMLNode.link("/freenet:USK@QRZAI1nSm~dAY2hTdzVWXmEhkaI~dso0OadnppBR7kE,wq5rHGBI7kpChBe4yRmgBChIGDug7Xa5SG9vYGXdxR0,AQACAAE/frost/14/"),
			                HTMLNode.link(ExternalLinkToadlet.escape("http://jtcfrost.sourceforge.net/")),
			                HTMLNode.link("/SSK@ugb~uuscsidMI-Ze8laZe~o3BUIb3S50i25RIwDH99M,9T20t3xoG-dQfMO94LGOl9AxRTkaz~TykFY-voqaTQI,AQACAAE/FAFS-49/files/frost.htm")});
		li = ul.addChild("li");
		NodeL10n.getBase().addL10nSubstitution(li, "ChatForumsToadlet.sone",
		       new String[] { "sone"},
			       new HTMLNode[] {
				   HTMLNode.link("/USK@nwa8lHa271k2QvJ8aa0Ov7IHAV-DFOCFgmDt3X6BpCI,DuQSUZiI~agF8c-6tjsFFGuZ8eICrzWCILB60nT8KKo,AQACAAE/sone/43/")});
		contentBox.addChild("p", l10n("content2"));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}
	
	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		// FIXME we should really refactor this boilerplate stuff out somehow...
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(node.clientCore.formPassword)) {
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			headers.put("Location", path());
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		if(request.isPartSet("loadFreetalk")) {
			node.executor.execute(new Runnable() {

				@Override
				public void run() {
					if(!node.pluginManager.isPluginLoaded("plugins.WebOfTrust.WebOfTrust")) {
						node.pluginManager.startPluginOfficial("WebOfTrust", true, false, false);
					}
				}
			});
			node.executor.execute(new Runnable() {

				@Override
				public void run() {
					if(!node.pluginManager.isPluginLoaded("plugins.Freetalk.Freetalk")) {
						node.pluginManager.startPluginOfficial("Freetalk", true, false, false);
					}
				}
			});
			try {
				// Wait a little to ensure we have at least started loading them.
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// Ignore
			}
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			headers.put("Location", PproxyToadlet.PATH);
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		} else {
			MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
			headers.put("Location", path());
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		}
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
