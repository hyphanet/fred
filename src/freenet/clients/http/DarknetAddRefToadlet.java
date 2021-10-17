package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeFile;
import freenet.support.HTMLNode;
import freenet.support.SimpleFieldSet;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileBucket;

public class DarknetAddRefToadlet extends Toadlet {

	private final Node node;
	private final DarknetConnectionsToadlet friendsToadlet;
	
	protected DarknetAddRefToadlet(Node n, HighLevelSimpleClient client, DarknetConnectionsToadlet friendsToadlet) {
		super(client);
		this.node = n;
		this.friendsToadlet = friendsToadlet;
	}

	public void handleMethodGET(URI uri, final HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
        if(!ctx.checkFullAccess(this))
            return;
		
		String path = uri.getPath();
		if(path.endsWith(NodeFile.InstallerWindows.getFilename())) {
			File installer = node.nodeUpdater.getInstallerWindows();
			if(installer != null) {
				FileBucket bucket = new FileBucket(installer, true, false, false, false);
				this.writeReply(ctx, 200, "application/x-msdownload", "OK", bucket);
				return;
			}
		}
		
		if(path.endsWith(NodeFile.InstallerNonWindows.getFilename())) {
			File installer = node.nodeUpdater.getInstallerNonWindows();
			if(installer != null) {
				FileBucket bucket = new FileBucket(installer, true, false, false, false);
				this.writeReply(ctx, 200, "application/x-java-archive", "OK", bucket);
				return;
			}
		}
		
		PageMaker pageMaker = ctx.getPageMaker();
		
		PageNode page = pageMaker.getPageNode(l10n("title"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(ctx.getAlertManager().createSummary());
		
		HTMLNode boxContent = pageMaker.getInfobox("infobox-information", l10n("explainBoxTitle"), contentNode, "darknet-explanations", true);
		boxContent.addChild("p", l10n("explainBox1"));
		boxContent.addChild("p", l10n("explainBox2"));
				
		File installer = node.nodeUpdater.getInstallerWindows();
		String shortFilename = NodeFile.InstallerWindows.getFilename();
		
		HTMLNode p = boxContent.addChild("p");
		
		if(installer != null)
			NodeL10n.getBase().addL10nSubstitution(p, "DarknetAddRefToadlet.explainInstallerWindows", new String[] { "filename", "get-windows" },
					new HTMLNode[] { HTMLNode.text(installer.getCanonicalPath()), HTMLNode.link(path()+shortFilename) });
		else
			NodeL10n.getBase().addL10nSubstitution(p, "DarknetAddRefToadlet.explainInstallerWindowsNotYet", new String[] { "link" }, new HTMLNode[] { HTMLNode.link("/"+node.nodeUpdater.getInstallerWindowsURI().toString()) });
		
		installer = node.nodeUpdater.getInstallerNonWindows();
		shortFilename = NodeFile.InstallerNonWindows.getFilename();
		
		boxContent.addChild("#", " ");
		
		p = boxContent.addChild("p");
		
		if(installer != null)
			NodeL10n.getBase().addL10nSubstitution(p, "DarknetAddRefToadlet.explainInstallerNonWindows", new String[] { "filename", "get-nonwindows", "shortfilename" },
					new HTMLNode[] { HTMLNode.text(installer.getCanonicalPath()), HTMLNode.link(path()+shortFilename), HTMLNode.text(shortFilename) });
		else
			NodeL10n.getBase().addL10nSubstitution(p, "DarknetAddRefToadlet.explainInstallerNonWindowsNotYet", new String[] { "link", "shortfilename" }, new HTMLNode[] { HTMLNode.link("/"+node.nodeUpdater.getInstallerNonWindowsURI().toString()), HTMLNode.text(shortFilename) });
			
		
		ConnectionsToadlet.drawAddPeerBox(contentNode, ctx, false, friendsToadlet.path());
		
		friendsToadlet.drawNoderefBox(contentNode, getNoderef(), pageMaker.advancedMode(request, this.container));
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	protected SimpleFieldSet getNoderef() {
		return node.exportDarknetPublicFieldSet();
	}
	
	private static String l10n(String string) {
		return NodeL10n.getBase().getString("DarknetAddRefToadlet."+string);
	}

	static final String PATH = "/addfriend/";

	@Override
	public String path() {
		return PATH;
	}
}
