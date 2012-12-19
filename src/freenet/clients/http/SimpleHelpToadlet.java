/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

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

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {

		
		PageNode page = ctx.getPageMaker().getPageNode("Freenet " + NodeL10n.getBase().getString("FProxyToadlet.help"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		// Description infobox
		HTMLNode helpScreenContent1 = ctx.getPageMaker().getInfobox("infobox-content", NodeL10n.getBase().getString("SimpleHelpToadlet.descriptionTitle"), contentNode, "freenet-description", true);
		helpScreenContent1.addChild("#", NodeL10n.getBase().getString("SimpleHelpToadlet.descriptionText"));
		
		// Definitions infobox
		HTMLNode helpScreenContent2 = ctx.getPageMaker().getInfobox("infobox-content", NodeL10n.getBase().getString("SimpleHelpToadlet.definitionsTitle"), contentNode, "freenet-definitions", true);
		
		HTMLNode table = helpScreenContent2.addChild("table", new String[]{"border", "style"}, new String[]{"0", "border: none"});
       
                HTMLNode row = table.addChild("tr");
                row.addChild("td", "style", "border: none");
              
		row.addChild("#", NodeL10n.getBase().getString("SimpleHelpToadlet.CHK"));
		row.addChild("br");
		row.addChild("#", NodeL10n.getBase().getString("SimpleHelpToadlet.SSK"));
		row.addChild("br");
		row.addChild("#", NodeL10n.getBase().getString("SimpleHelpToadlet.USK"));
		
		// Port forwarding, etc.	
		HTMLNode helpScreenContent3 = ctx.getPageMaker().getInfobox("infobox-content", NodeL10n.getBase().getString("SimpleHelpToadlet.connectivityTitle"), contentNode, "freenet-connectivity", true);
		helpScreenContent3.addChild("#", NodeL10n.getBase().getString("SimpleHelpToadlet.connectivityText"));
		
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}

	@Override
	public String path() {
		return "/help/";
	}
	
}
