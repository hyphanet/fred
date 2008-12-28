/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;
import freenet.l10n.L10n;

/**
 * Simple Help Toadlet.  Provides an offline means of looking up some basic info, howtos, and FAQ
 * Likely to be superceded someday by an offical Freesite and binary blob included in install package.
 * @author Juiceman
 */
public class SimpleHelpToadlet extends Toadlet {
	SimpleHelpToadlet(HighLevelSimpleClient client, NodeClientCore c) {
		super(client);
		this.core=c;
	}
	
	final NodeClientCore core;
	
	@Override
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet " + L10n.getString("SimpleHelpToadlet.help"), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		HTMLNode helpScreenBox1 = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-content", L10n.getString("SimpleHelpToadlet.descriptionTitle")));
		HTMLNode helpScreenContent1 = ctx.getPageMaker().getContentNode(helpScreenBox1);
		HTMLNode p1 = helpScreenContent1.addChild("p1");
		L10n.addL10nHTML(p1, "SimpleHelpToadlet.descriptionText");
		
		
		HTMLNode helpScreenBox2 = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-content", L10n.getString("SimpleHelpToadlet.connectivityTitle")));
		HTMLNode helpScreenContent2 = ctx.getPageMaker().getContentNode(helpScreenBox2);
		HTMLNode p2 = helpScreenContent2.addChild("p2");
		L10n.addL10nHTML(p2, "SimpleHelpToadlet.connectivityText");
		
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}
	
	@Override
	public String supportedMethods() {
		return "GET";
	}
	
}
