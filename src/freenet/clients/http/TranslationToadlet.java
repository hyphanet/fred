/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleFieldSet;
import freenet.support.SimpleFieldSet.KeyIterator;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;

/**
 * A toadlet dedicated to translations ... and easing the work of translators
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class TranslationToadlet extends Toadlet {
	public static final String TOADLET_URL = "/translation/";
	private final NodeClientCore core;
	private static final SimpleFieldSet DEFAULT_TRANSLATION = L10n.getDefaultLanguageTranslation();
	
	TranslationToadlet(HighLevelSimpleClient client, NodeClientCore core) {
		super(client);
		this.core = core;
	}
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", "You are not permitted access to this page");
			return;
		}
		
		if (request.isParameterSet("getOverrideTranlationFile")) {
			SimpleFieldSet sfs = L10n.getOverrideForCurrentLanguageTranslation();
			if(sfs == null) {
				super.sendErrorPage(ctx, 503 /* Service Unavailable */, "Service Unavailable", "There is no custom translation available.");
				return;
			}
			byte[] data = sfs.toOrderedString().getBytes("UTF-8");
			MultiValueTable head = new MultiValueTable();
			head.put("Content-Disposition", "attachment; filename=\"" + L10n.PREFIX +L10n.getSelectedLanguage()+ L10n.OVERRIDE_SUFFIX + '"');
			ctx.sendReplyHeaders(200, "Found", head, "text/plain", data.length);
			ctx.writeData(data);
			return;
		} else if (request.isParameterSet("translation_updated")) {
			String key = request.getParam("translation_updated");
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Translation updated!", true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
			HTMLNode legendTable = translationNode.addChild("table", "class", "translation");
			
			HTMLNode legendRow = legendTable.addChild("tr").addChild("b");
			legendRow.addChild("td", "class", "translation-key", "Translation key");
			legendRow.addChild("td", "class", "translation-key", "Original (english version)");
			legendRow.addChild("td", "class", "translation-key", "Current translation");
			
			HTMLNode contentRow = legendTable.addChild("tr");
			contentRow.addChild("td", "class", "translation-key",
					key
			);
			contentRow.addChild("td", "class", "translation-orig",
					L10n.getDefaultString(key)
			);
			contentRow.addChild("td", "class", "translation-new",
					L10n.getString(key)
			);
			
			HTMLNode footer = translationNode.addChild("div", "class", "warning");
			footer.addChild("a", "href", TOADLET_URL+"?getOverrideTranlationFile").addChild("#", "Download the override translation file");
			footer.addChild("%", "&nbsp;&nbsp;");
			footer.addChild("a", "href", TOADLET_URL+"?translate="+key).addChild("#", "Re-edit the translation");
			footer.addChild("%", "&nbsp;&nbsp;");
			footer.addChild("a", "href", TOADLET_URL).addChild("#", "Return to the translation page");

			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;				
		} else if (request.isParameterSet("translate")) {
			String key = request.getParam("translate");
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Translation update", true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
			HTMLNode updateForm =  ctx.addFormChild(translationNode, TOADLET_URL, "trans_update");
			HTMLNode legendTable = updateForm.addChild("table", "class", "translation");
			
			HTMLNode legendRow = legendTable.addChild("tr");
			legendRow.addChild("td", "class", "translation-key", "Translation key");
			legendRow.addChild("td", "class", "translation-key", "Original (english version)");
			legendRow.addChild("td", "class", "translation-key", "Current translation");
			
			HTMLNode contentRow = legendTable.addChild("tr");
			contentRow.addChild("td", "class", "translation-key",
					key
			);
			contentRow.addChild("td", "class", "translation-orig",
					L10n.getDefaultString(key)
			);
			
			contentRow.addChild("td", "class", "translation-new").addChild(
					"textarea",
					new String[] { "name", "rows", "cols" },
					new String[] { "trans", "3", "80" },
					L10n.getString(key));
			
			contentRow.addChild("input", 
					new String[] { "type", "name", "value" }, 
					new String[] { "hidden", "key", key
			});

			updateForm.addChild("input", 
					new String[] { "type", "name", "value" }, 
					new String[] { "submit", "translation_update", "Update the translation!"
			});
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		} else if (request.isParameterSet("remove")) {
			String key = request.getParam("remove");
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Remove a translation override key", true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", "You are about to remove a translation override key!"));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#", "Are you sure that you want to remove the following translation key : (" + key + " - " + L10n.getString(key) + ") ?");
			HTMLNode removeForm = ctx.addFormChild(content.addChild("p"), TOADLET_URL, "remove_confirmed");
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "remove_confirm", key });
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_confirmed", "Remove" });
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Translation update", true, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
		HTMLNode translationHeaderNode = translationNode.addChild("p");
		translationHeaderNode.addChild("#", "You are currently contributing to the " + L10n.getSelectedLanguage() + " translation :");
		translationHeaderNode.addChild("a", "href", TOADLET_URL+"?getOverrideTranlationFile").addChild("#", " Download the override translation file");
		HTMLNode legendTable = translationNode.addChild("table", "class", "translation");
		
		HTMLNode legendRow = legendTable.addChild("tr");
		legendRow.addChild("td", "class", "translation-key", "Translation key");
		legendRow.addChild("td", "class", "translation-key", "Original (english version)");
		legendRow.addChild("td", "class", "translation-key", "Current translation");
		
		SimpleFieldSet sfs = L10n.getCurrentLanguageTranslation();
		if(sfs != null) {
			KeyIterator it = DEFAULT_TRANSLATION.keyIterator("");

			while(it.hasNext()) {
				String key = it.nextKey();

				HTMLNode contentRow = legendTable.addChild("tr");
				contentRow.addChild("td", "class", "translation-key",
						key
				);
				contentRow.addChild("td", "class", "translation-orig",
						L10n.getDefaultString(key)
				);

				contentRow.addChild("td", "class", "translation-new").addChild(_setOrRemoveOverride(key));
			}
		}
		
		this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", "You are not permitted access to this page");
			return;
		}
		
		final boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		final String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(logMINOR) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			redirectTo(ctx, "/");
			return;
		}
		
		if(request.getPartAsString("translation_update", 32).length() > 0){
			String key = request.getPartAsString("key", 256);
			L10n.setOverride(key, new String(BucketTools.toByteArray(request.getPart("trans")), "UTF-8"));
			
			redirectTo(ctx, TOADLET_URL+"?translation_updated="+key);
			return;
		} else if(request.getPartAsString("remove_confirmed", 32).length() > 0) {
			String key = request.getPartAsString("remove_confirm", 256).trim();
			L10n.setOverride(key, "");
			
			redirectTo(ctx, TOADLET_URL+"?translation_updated="+key);
			return;
		}else // Shouldn't reach that point!
			redirectTo(ctx, "/");
	}
	
	private void redirectTo(ToadletContext ctx, String target) throws ToadletContextClosedException, IOException {
		MultiValueTable headers = new MultiValueTable();
		headers.put("Location", target);
		ctx.sendReplyHeaders(302, "Found", headers, null, 0);
		return;
	}
	
	public String supportedMethods() {
		return "GET, POST";
	}
	
	private HTMLNode _setOrRemoveOverride(String key) {
		String value = L10n.getString(key, true);
		
		HTMLNode translationField = new HTMLNode("span", "class", "translate_it");
		if(value == null) {
			translationField.addChild("#", L10n.getDefaultString(key));
			translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?translate=" + key).addChild("small", " (translate it in your native language!)");
		} else {
			translationField.addChild("#", L10n.getString(key));
			translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?translate=" + key).addChild("small", " (update the translation)");
			if(L10n.isOverridden(key))
				translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?remove=" + key).addChild("small", " (Remove the translation override!)");
		}
		
		return translationField;
	}
}
