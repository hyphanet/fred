package freenet.clients.http;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.api.HTTPRequest;

public class DarknetAddRefToadlet extends Toadlet {

	private final Node node;
	private final NodeClientCore core;
	
	protected DarknetAddRefToadlet(Node n, NodeClientCore core, HighLevelSimpleClient client) {
		super(client);
		this.node = n;
		this.core = core;
	}

	public void handleMethodGET(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String path = uri.getPath();
		if(path.endsWith("myref.fref")) {
			SimpleFieldSet fs = getNoderef();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			MultiValueTable<String, String> extraHeaders = new MultiValueTable<String, String>();
			// Force download to disk
			extraHeaders.put("Content-Disposition", "attachment; filename=myref.fref");
			this.writeReply(ctx, 200, "application/x-freenet-reference", "OK", extraHeaders, sw.toString());
			return;
		}

		if(path.endsWith("myref.txt")) {
			SimpleFieldSet fs = getNoderef();
			StringWriter sw = new StringWriter();
			fs.writeTo(sw);
			this.writeTextReply(ctx, 200, "OK", sw.toString());
			return;
		}
		
		PageMaker pageMaker = ctx.getPageMaker();
		
		PageNode page = pageMaker.getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(core.alerts.createSummary());
		
		HTMLNode boxContent = pageMaker.getInfobox("infobox-information", l10n("explainBoxTitle"), contentNode, "darknet-explanations", true);
		boxContent.addChild("p", l10n("explainBox1"));
		boxContent.addChild("p", l10n("explainBox2"));
		
		ConnectionsToadlet.drawAddPeerBox(contentNode, ctx, false, "/friends/");
		
		ConnectionsToadlet.drawNoderefBox(contentNode, ctx, getNoderef());
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	protected SimpleFieldSet getNoderef() {
		return node.exportDarknetPublicFieldSet();
	}
	
	private static String l10n(String string) {
		return L10n.getString("DarknetAddRefToadlet."+string);
	}
	

	@Override
	public String path() {
		return "/addfriend/";
	}
}
