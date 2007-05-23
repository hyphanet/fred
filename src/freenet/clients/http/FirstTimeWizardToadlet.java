/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * A first time wizard aimed to ease the configuration of the node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final Node node;
	private final NodeClientCore core;
	private final Config config;
	
	
	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node) {
		super(client);
		this.node = node;
		this.core = node.clientCore;
		this.config = node.config;
	}
	
	public static final String TOADLET_URL = "/wizard/";
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		int currentStep = request.getIntParam("step");
		
		if(currentStep == 1) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step1Title"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode languageInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode languageInfoboxHeader = languageInfobox.addChild("div", "class", "infobox-header");
			HTMLNode languageInfoboxContent = languageInfobox.addChild("div", "class", "infobox-content");
			
			languageInfoboxHeader.addChild("#", l10n("selectLanguage"));
			languageInfoboxContent.addChild("#", l10n("selectLanguageLong"));
			HTMLNode languageForm = ctx.addFormChild(languageInfoboxContent, ".", "languageForm");
			HTMLNode result = languageForm.addChild("select", "name", "language");
			
			for(int i=0; i<L10n.AVAILABLE_LANGUAGES.length; i++) {
				
				if("en".equals(L10n.AVAILABLE_LANGUAGES[i]))
					result.addChild("option", new String[] { "value", "selected" }, new String[] {
							L10n.AVAILABLE_LANGUAGES[i], "selected" }, L10n.AVAILABLE_LANGUAGES[i]);
				else
					result.addChild("option", "value", L10n.AVAILABLE_LANGUAGES[i], L10n.AVAILABLE_LANGUAGES[i]);
			}
			
			languageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "languageF", L10n.getString("Toadlet.clickHere")});
			languageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));
		welcomeInfoboxContent.addChild("#", l10n("welcomeInfoboxContent1"));
		welcomeInfoboxContent.addChild("a", "href", "?step=1").addChild("#", L10n.getString("Toadlet.clickHere"));
		this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}
		
		if(request.isPartSet("languageF")) {
			String selectedLanguage = request.getPartAsString("language", 4);
			
			try {
				config.get("node").set("l10n", selectedLanguage);
				Logger.normal(this, "The language has been set to "+ selectedLanguage);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e);
			}
			super.writeTemporaryRedirect(ctx, "step2", TOADLET_URL+"?step=2");
			return;
		}
		
		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}
	
	private String l10n(String key) {
		return L10n.getString("FirstTimeWizardToadlet."+key);
	}

	public String supportedMethods() {
		return "GET, POST";
	}
}
