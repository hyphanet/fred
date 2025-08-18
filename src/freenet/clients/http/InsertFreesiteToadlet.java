package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/** This is just documentation, it will be replaced with a plugin wizard eventually. */
public class InsertFreesiteToadlet extends Toadlet {

	protected InsertFreesiteToadlet(HighLevelSimpleClient client) {
		super(client);
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		PageNode page = ctx.getPageMaker().getPageNode(l10n("title"), ctx);
		HTMLNode contentNode = page.getContentNode();
		
		contentNode.addChild(ctx.getAlertManager().createSummary());
		
		HTMLNode contentBox = ctx.getPageMaker().getInfobox("infobox-information", l10n("title"), contentNode, "freesite-insert", true);
		
		contentBox.addChild("p", l10n("content1"));
		
		NodeL10n.getBase().addL10nSubstitution(contentBox.addChild("p"), "InsertFreesiteToadlet.contentFlogHelper", new String[] { "plugins" }, new HTMLNode[] { HTMLNode.link(PproxyToadlet.PATH) }); 
		
		NodeL10n.getBase().addL10nSubstitution(contentBox.addChild("p"), "InsertFreesiteToadlet.content2",
		        new String[] { "jsite-http", "jsite-freenet", "jsite-freenet-version", "jsite-info" },
				new HTMLNode[] {
						HTMLNode.link(ExternalLinkToadlet.escape("http://downloads.freenetproject.org/alpha/jSite/")),
						HTMLNode.link("/SSK@1waTsw46L9-JEQ8yX1khjkfHcn--g0MlMsTlYHax9zQ,oYyxr5jyFnaTsVGDQWk9e3ddOWGKnqEASxAk08MHT2Y,AQACAAE/jSite-15/jSite-0.14-jar-with-dependencies.jar"),
						HTMLNode.text("0.14"),
						HTMLNode.link("/SSK@1waTsw46L9-JEQ8yX1khjkfHcn--g0MlMsTlYHax9zQ,oYyxr5jyFnaTsVGDQWk9e3ddOWGKnqEASxAk08MHT2Y,AQACAAE/jSite-15/"),
				});
		contentBox.addChild("p", l10n("content3"));
		HTMLNode ul = contentBox.addChild("ul");
		HTMLNode li = ul.addChild("li");
		li.addChild("a", "href", "/SSK@940RYvj1-aowEHGsb5HeMTigq8gnV14pbKNsIvUO~-0,FdTbR3gIz21QNfDtnK~MiWgAf2kfwHe-cpyJXuLHdOE,AQACAAE/publish-3/", "Publish!");
		li.addChild("#", " - "+l10n("publishExplanation"));
		li = ul.addChild("li");
		li.addChild("a", "href", "/SSK@8r-uSRcJPkAr-3v3YJR16OCx~lyV2XOKsiG4MOQQBMM,P42IgNemestUdaI7T6z3Og6P-Hi7g9U~e37R3kWGVj8,AQACAAE/freesite-HOWTO-4/", "Freesite HOWTO");
		li.addChild("#", " - "+l10n("freesiteHowtoExplanation"));
		
		NodeL10n.getBase().addL10nSubstitution(contentBox.addChild("p"), "InsertFreesiteToadlet.contentThingamablog",
		        new String[] { "thingamablog", "thingamablog-freenet" },
		        new HTMLNode[] { HTMLNode.link(ExternalLinkToadlet.escape("http://downloads.freenetproject.org/alpha/thingamablog/thingamablog.zip")),
		                HTMLNode.link("/CHK@o8j9T2Ghc9cfKMLvv9aLrHbvW5XiAMEGwGDqH2UANTk,sVxLdxoNL-UAsvrlXRZtI5KyKlp0zv3Ysk4EcO627V0,AAIC--8/thingamablog.zip") });
		
		this.writeHTMLReply(ctx, 200, "OK", page.generate());
	}

	private static String l10n(String string) {
		return NodeL10n.getBase().getString("InsertFreesiteToadlet." + string);
	}

	@Override
	public String path() {
		return "/insertsite/";
	}
}
