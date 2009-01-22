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

		
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Freenet " + L10n.getString("FProxyToadlet.help"), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		// Description infobox
		HTMLNode helpScreenBox1 = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-content", L10n.getString("SimpleHelpToadlet.descriptionTitle")));
		HTMLNode helpScreenContent1 = ctx.getPageMaker().getContentNode(helpScreenBox1);
		helpScreenContent1.addChild("#", L10n.getString("SimpleHelpToadlet.descriptionText"));
		
		// Definitions infobox
		HTMLNode helpScreenBox2 = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-content", L10n.getString("SimpleHelpToadlet.definitionsTitle")));
		HTMLNode helpScreenContent2 = ctx.getPageMaker().getContentNode(helpScreenBox2);
		
		HTMLNode table = helpScreenContent2.addChild("table", new String[]{"border", "style"}, new String[]{"0", "border: none"});
       
                HTMLNode row = table.addChild("tr");
                HTMLNode cell = row.addChild("td", "style", "border: none");
              
		        // cell.addChild("#", " ");
                // }
                // cell = row.addChild("td", "style", "border: none");
                // cell.addChild("a", new String[]{"href", "title"}, new String[]{ ctx.fixLink('/' + item.getKey()), item.getDescription()}, item.getName());
     
		row.addChild("#", L10n.getString("SimpleHelpToadlet.CHK"));
		row.addChild("br");
		row.addChild("#", L10n.getString("SimpleHelpToadlet.SSK"));
		row.addChild("br");
		row.addChild("#", L10n.getString("SimpleHelpToadlet.USK"));
		
		// helpScreenContent2.addchild("#", L10n.getString("SimpleHelpToadlet.CHK"));
		// helpScreenContent2.addchild("br");
		// helpScreenContent2.addchild("#", L10n.getString("SimpleHelpToadlet.SSK"));
		// helpScreenContent2.addchild("br");
		// helpScreenContent2.addchild("#", L10n.getString("SimpleHelpToadlet.USK"));
		
		
		// Port forwarding, etc.	
		HTMLNode helpScreenBox3 = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-content", L10n.getString("SimpleHelpToadlet.connectivityTitle")));
		HTMLNode helpScreenContent3 = ctx.getPageMaker().getContentNode(helpScreenBox3);
		helpScreenContent3.addChild("#", L10n.getString("SimpleHelpToadlet.connectivityText"));
		
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}
	
	@Override
	public String supportedMethods() {
		return "GET";
	}
	
}
