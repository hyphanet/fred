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
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		boolean showEverything = !request.isParameterSet("toTranslateOnly");
		
		if (request.isParameterSet("getOverrideTranlationFile")) {
			SimpleFieldSet sfs = L10n.getOverrideForCurrentLanguageTranslation();
			if(sfs == null) {
				super.sendErrorPage(ctx, 503 /* Service Unavailable */, "Service Unavailable", l10n("noCustomTranslations"));
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
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("translationUpdatedTitle"), true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
			HTMLNode legendTable = translationNode.addChild("table", "class", "translation");
			
			HTMLNode legendRow = legendTable.addChild("tr").addChild("b");
			legendRow.addChild("td", "class", "translation-key", l10n("translationKeyLabel"));
			legendRow.addChild("td", "class", "translation-key", l10n("originalVersionLabel"));
			legendRow.addChild("td", "class", "translation-key", l10n("currentTranslation"));
			
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
			footer.addChild("a", "href", TOADLET_URL+"?getOverrideTranlationFile").addChild("#", l10n("downloadTranslationsFile"));
			footer.addChild("%", "&nbsp;&nbsp;");
			footer.addChild("a", "href", TOADLET_URL+"?translate="+key+ (showEverything ? "" : "&toTranslateOnly")).addChild("#", l10n("reEdit"));
			footer.addChild("%", "&nbsp;&nbsp;");
			footer.addChild("a", "href", TOADLET_URL + (showEverything ? "" : "?toTranslateOnly")).addChild("#", l10n("returnToTranslations"));

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
			legendRow.addChild("td", "class", "translation-key", l10n("translationKeyLabel"));
			legendRow.addChild("td", "class", "translation-key", l10n("originalVersionLabel"));
			legendRow.addChild("td", "class", "translation-key", l10n("currentTranslation"));
			
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
					new String[] { "submit", "translation_update", l10n("updateTranslationCommand")
			});
			if(!showEverything)
				updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "toTranslateOnly", key });
			
			updateForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		} else if (request.isParameterSet("remove")) {
			String key = request.getParam("remove");
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("removeOverrideTitle"), true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode infobox = contentNode.addChild(ctx.getPageMaker().getInfobox("infobox-warning", l10n("removeOverrideWarningTitle")));
			HTMLNode content = ctx.getPageMaker().getContentNode(infobox);
			content.addChild("p").addChild("#",
					L10n.getString("TranslationToadlet.confirmRemoveOverride", new String[] { "key", "value" },
							new String[] { key, L10n.getString(key) }));
			HTMLNode removeForm = ctx.addFormChild(content.addChild("p"), TOADLET_URL, "remove_confirmed");
			if(!showEverything)
				removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "toTranslateOnly", key });
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "remove_confirm", key });
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "remove_confirmed", l10n("remove") });
			removeForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel") });
			
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("translationUpdateTitle"), true, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
		HTMLNode translationHeaderNode = translationNode.addChild("p");
		translationHeaderNode.addChild("#", l10n("contributingToLabelWithLang", "lang", L10n.getSelectedLanguage()));
		translationHeaderNode.addChild("a", "href", TOADLET_URL+"?getOverrideTranlationFile").addChild("#", l10n("downloadTranslationsFile"));
		translationHeaderNode.addChild("#", " ");
		if(showEverything)
			translationHeaderNode.addChild("a", "href", TOADLET_URL+"?toTranslateOnly").addChild("#", l10n("hideAlreadyTranslated"));
		else
			translationHeaderNode.addChild("a", "href", TOADLET_URL).addChild("#", l10n("showEverything"));
		HTMLNode legendTable = translationNode.addChild("table", "class", "translation");
		
		HTMLNode legendRow = legendTable.addChild("tr");
		legendRow.addChild("td", "class", "translation-key", l10n("translationKeyLabel"));
		legendRow.addChild("td", "class", "translation-key", l10n("originalVersionLabel"));
		legendRow.addChild("td", "class", "translation-key", l10n("currentTranslation"));
		
		SimpleFieldSet sfs = L10n.getCurrentLanguageTranslation();
		if(sfs != null) {
			KeyIterator it = DEFAULT_TRANSLATION.keyIterator("");

			while(it.hasNext()) {
				String key = it.nextKey();
				boolean isOverriden = L10n.isOverridden(key);
				if(!showEverything && isOverriden) continue;
				HTMLNode contentRow = legendTable.addChild("tr");
				contentRow.addChild("td", "class", "translation-key",
						key
				);
				contentRow.addChild("td", "class", "translation-orig",
						L10n.getDefaultString(key)
				);

				contentRow.addChild("td", "class", "translation-new").addChild(_setOrRemoveOverride(key, isOverriden, showEverything));
			}
		}
		
		this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
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
			L10n.setOverride(key, new String(BucketTools.toByteArray(request.getPart("trans")), "UTF-8").trim());
			
			redirectTo(ctx, TOADLET_URL+"?translation_updated="+key+ (request.isPartSet("toTranslateOnly") ? "&toTranslateOnly" : ""));
			return;
		} else if(request.getPartAsString("remove_confirmed", 32).length() > 0) {
			String key = request.getPartAsString("remove_confirm", 256).trim();
			L10n.setOverride(key, "");
			
			redirectTo(ctx, TOADLET_URL+"?translation_updated="+key+ (request.isPartSet("toTranslateOnly") ? "&toTranslateOnly" : ""));
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
	
	private HTMLNode _setOrRemoveOverride(String key, boolean isOverriden, boolean showEverything) {
		String value = L10n.getString(key, true);
		
		HTMLNode translationField = new HTMLNode("span", "class", "translate_it");
		if(value == null) {
			translationField.addChild("#", L10n.getDefaultString(key));
			translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?translate=" + key + (showEverything ? "" : "&toTranslateOnly")).addChild("small", l10n("bracketTranslateIt"));
		} else {
			translationField.addChild("#", L10n.getString(key));
			translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?translate=" + key + (showEverything ? "" : "&toTranslateOnly")).addChild("small", l10n("bracketUpdateTranslation"));
			if(isOverriden)
				translationField.addChild("a", "href", TranslationToadlet.TOADLET_URL+"?remove=" + key + (showEverything ? "" : "&toTranslateOnly")).addChild("small", l10n("bracketRemoveOverride"));
		}
		
		return translationField;
	}
	
	private String l10n(String key) {
		return L10n.getString("TranslationToadlet."+key);
	}
	
	private String l10n(String key, String pattern, String value) {
		return L10n.getString("TranslationToadlet."+key, new String[] { pattern }, new String[] { value });
	}
}
