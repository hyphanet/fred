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
	
	TranslationToadlet(HighLevelSimpleClient client, NodeClientCore core) {
		super(client);
		this.core = core;
	}
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", "You are not permitted access to this page");
			return;
		}
		
		if (request.isParameterSet("getTranlationFile")) {
			byte[] data = L10n.getCurrentLanguageTranslation().toOrderedString().getBytes("UTF-8");
			MultiValueTable head = new MultiValueTable();
			head.put("Content-Disposition", "attachment; filename=\"" + L10n.PREFIX +L10n.getSelectedLanguage()+ L10n.SUFFIX + '"');
			ctx.sendReplyHeaders(200, "Found", head, "text/plain", data.length);
			ctx.writeData(data);
			return;
		} else if (request.isParameterSet("getOverrideTranlationFile")) {
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
		} else if (request.isParameterSet("transupdated")) {
			String key = request.getParam("transupdated");
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
			footer.addChild("a", "href", TOADLET_URL+"?getTranlationFile").addChild("#", "Download the full translation file");
			footer.addChild("%", "&nbsp;&nbsp;");
			footer.addChild("a", "href", "/").addChild("#", "Return to the main page");

			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;				
		} else if (request.isParameterSet("translate")) {
			String key = request.getParam("translate");
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Translation update", true, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
			HTMLNode updateForm =  ctx.addFormChild(translationNode, TOADLET_URL, "trans_update");
			HTMLNode legendTable = updateForm.addChild("table", "class", "translation");
			
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
			
			contentRow.addChild("td", "class", "translation-new").addChild(
					"textarea",
					new String[] { "name", "rows", "cols", "value" },
					new String[] { "trans", "3", "80", L10n.getString(key)
					});
			
			updateForm.addChild("input", 
					new String[] { "type", "name", "value" }, 
					new String[] { "hidden", "key", key
			});

			contentRow = legendTable.addChild("tr");
			contentRow.addChild("input", 
					new String[] { "type", "name", "value" }, 
					new String[] { "submit", "trupdate", "Update the translation!"
			});
			contentRow.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", "Cancel" });
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode("Translation update", true, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

		HTMLNode translationNode = contentNode.addChild("div", "class", "translation");
		HTMLNode updateForm =  ctx.addFormChild(translationNode, TOADLET_URL, "trans_update");
		HTMLNode legendTable = updateForm.addChild("table", "class", "translation");
		
		HTMLNode legendRow = legendTable.addChild("tr").addChild("b");
		legendRow.addChild("td", "class", "translation-key", "Translation key");
		legendRow.addChild("td", "class", "translation-key", "Original (english version)");
		legendRow.addChild("td", "class", "translation-key", "Current translation");
		
		SimpleFieldSet sfs = L10n.getCurrentLanguageTranslation();
		if(sfs != null) {
			KeyIterator it = sfs.keyIterator("");

			while(it.hasNext()) {
				String key = it.nextKey();

				HTMLNode contentRow = legendTable.addChild("tr");
				contentRow.addChild("td", "class", "translation-key",
						key
				);
				contentRow.addChild("td", "class", "translation-orig",
						L10n.getDefaultString(key)
				);

				contentRow.addChild("td", "class", "translation-new").addChild(
						"input",
						new String[] { "type", "name", "value" },
						new String[] { "text", "trans", L10n.getString(key)
						});

				legendTable.addChild("td", 
						new String[] { "type", "name", "value" }, 
						new String[] { "text", "key", key
				});
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
		
		if(request.getPartAsString("trupdate", 32).length() > 0){
			String key = request.getPartAsString("key", 256);
			L10n.setOverride(key, new String(BucketTools.toByteArray(request.getPart("trans")), "UTF-8"));
			
			redirectTo(ctx, TOADLET_URL+"?transupdated="+key);
			return;
		} else // Shouldn't reach that point!
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

}
