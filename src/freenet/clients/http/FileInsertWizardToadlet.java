package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.filter.FilterOperation;
import freenet.clients.http.ContentFilterToadlet.ResultHandling;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class FileInsertWizardToadlet extends Toadlet implements LinkEnabledCallback {

	protected FileInsertWizardToadlet (HighLevelSimpleClient client, NodeClientCore clientCore) {
		super(client);
		this.core = clientCore;
	}

	final NodeClientCore core;
	
	// IMHO there isn't much point synchronizing these.
	private boolean rememberedLastTime;
	private boolean wasCanonicalLastTime;
	
	static final String PATH = "/insertfile/";
	
	@Override
	public String path() {
		return PATH;
	}
	
	public void reportCanonicalInsert() {
		rememberedLastTime = true;
		wasCanonicalLastTime = true;
	}
	
	public void reportRandomInsert() {
		rememberedLastTime = true;
		wasCanonicalLastTime = false;
	}
	
	public void handleMethodGET (URI uri, final HTTPRequest request, final ToadletContext ctx)
	        throws ToadletContextClosedException, IOException, RedirectException {

//		// We ensure that we have a FCP server running
//		if(!fcp.enabled){
//			writeError(NodeL10n.getBase().getString("QueueToadlet.fcpIsMissing"), NodeL10n.getBase().getString("QueueToadlet.pleaseEnableFCP"), ctx, false);
//			return;
//		}
//		if(!core.hasLoadedQueue()) {
//			writeError(NodeL10n.getBase().getString("QueueToadlet.notLoadedYetTitle"), NodeL10n.getBase().getString("QueueToadlet.notLoadedYet"), ctx, false);
//			return;
//		}

		if (container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
		    sendUnauthorizedPage(ctx);
			return;
		}
		
		final PageMaker pageMaker = ctx.getPageMaker();

		PageNode page = pageMaker.getPageNode(l10n("pageTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		/* add alert summary box */
		if (ctx.isAllowedFullAccess()) contentNode.addChild(ctx.getAlertManager().createSummary());

		contentNode.addChild(createInsertBox(pageMaker, ctx, ctx.isAdvancedModeEnabled()));
		if(ctx.isAdvancedModeEnabled())
			contentNode.addChild(createFilterBox(pageMaker, ctx));
		
		writeHTMLReply(ctx, 200, "OK", null, pageNode.generate());
	}
	
	private HTMLNode createInsertBox (PageMaker pageMaker, ToadletContext ctx, boolean isAdvancedModeEnabled) {
		/* the insert file box */
		InfoboxNode infobox = pageMaker.getInfobox(
		        NodeL10n.getBase().getString("QueueToadlet.insertFile"), "insert-queue", true);
		HTMLNode insertBox = infobox.outer;
		HTMLNode insertContent = infobox.content;
		insertContent.addChild("p", l10n("insertIntro"));
		NETWORK_THREAT_LEVEL seclevel = core.getNode().getSecurityLevels().getNetworkThreatLevel();
		HTMLNode insertForm = ctx.addFormChild(insertContent, QueueToadlet.PATH_UPLOADS, "queueInsertForm");
		boolean preselectSsk = (!rememberedLastTime && seclevel != NETWORK_THREAT_LEVEL.LOW)
				|| (rememberedLastTime && !wasCanonicalLastTime)
				|| seclevel == NETWORK_THREAT_LEVEL.MAXIMUM;
		HTMLNode input = insertForm.addChild("input",
		        new String[] { "type", "name", "value", "id" },
		        new String[] { "radio", "keytype", "CHK", "keytypeChk" });
		if (!preselectSsk) {
			input.addAttribute("checked", "checked");
		}
		insertForm.addChild("label",
		        new String[] { "for" },
		        new String[] { "keytypeChk" }
		        ).addChild("b", l10n("insertCanonicalTitle"));
		insertForm.addChild("#", ": "+l10n("insertCanonical"));
		if(isAdvancedModeEnabled)
			insertForm.addChild("#", " "+l10n("insertCanonicalAdvanced"));
		insertForm.addChild("br");
		input = insertForm.addChild("input",
		        new String[] { "type", "name", "value", "id" },
		        new String[] { "radio", "keytype", "SSK", "keytypeSsk" });
		if (preselectSsk) {
			input.addAttribute("checked", "checked");
		}
		insertForm.addChild("label",
            new String[] { "for" },
            new String[] { "keytypeSsk" }
            ).addChild("b", l10n("insertRandomTitle"));
		insertForm.addChild("#", ": "+l10n("insertRandom"));
		if(isAdvancedModeEnabled)
			insertForm.addChild("#", " "+l10n("insertRandomAdvanced"));
		if (isAdvancedModeEnabled) {
			insertForm.addChild("br");
			insertForm.addChild("input",
			        new String[] { "type", "name", "value", "id" },
			        new String[] { "radio", "keytype", "specify", "keytypeSpecify" });
			insertForm.addChild("label",
              new String[] { "for" },
              new String[] { "keytypeSpecify" }
              ).addChild("b", l10n("insertSpecificKeyTitle"));
			insertForm.addChild("#", ": "+l10n("insertSpecificKey")+" ");
			insertForm.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "text", "key", "KSK@" });
		}
		if (isAdvancedModeEnabled) {
			insertForm.addChild("br");
			insertForm.addChild("br");
			insertForm.addChild("input",
			        new String[] { "type", "name", "checked", "id" },
			        new String[] { "checkbox", "compress", "checked", "checkboxCompress" });
			insertForm.addChild("label",
              new String[] { "for" },
              new String[] { "checkboxCompress" },
              ' ' + NodeL10n.getBase().getString("QueueToadlet.insertFileCompressLabel"));
		} else {
			insertForm.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", "compress", "true" });
		}
		if(isAdvancedModeEnabled) {
			insertForm.addChild("br");
			insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.compatModeLabel")+": ");
			HTMLNode select = insertForm.addChild("select", "name", "compatibilityMode");
			for(CompatibilityMode mode : InsertContext.CompatibilityMode.values()) {
				if(mode == CompatibilityMode.COMPAT_UNKNOWN) continue;
				// FIXME l10n???
				HTMLNode option = select.addChild("option", "value", mode.name(),
				        NodeL10n.getBase().getString("InsertContext.CompatibilityMode."+mode.name()));
				if (mode == CompatibilityMode.COMPAT_DEFAULT) option.addAttribute("selected", "");
			}
			insertForm.addChild("br");
			insertForm.addChild("#", l10n("splitfileCryptoKeyLabel")+": ");
			insertForm.addChild("input",
			        new String[] { "type", "name", "maxlength" },
			        new String[] { "text", "overrideSplitfileKey", "64" });
		}
		insertForm.addChild("br");
		insertForm.addChild("br");
		// Local file browser
		if (ctx.isAllowedFullAccess()) {
			insertForm.addChild("#",
			        NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseLabel")+": ");
			insertForm.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "submit", "insert-local",
			                NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseButton") + "..." });
			insertForm.addChild("br");
		}
		insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileLabel") + ": ");
		insertForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", 
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "insert",
		                NodeL10n.getBase().getString("QueueToadlet.insertFileInsertFileLabel") });
		insertForm.addChild("#", " \u00a0 ");
		
		return insertBox;
	}
	
	private HTMLNode createFilterBox (PageMaker pageMaker, ToadletContext ctx) {
		/* the insert file box */
		InfoboxNode infobox = pageMaker.getInfobox(
		        l10n("previewFilterFile"), "insert-queue", true);
		HTMLNode insertBox = infobox.outer;
		HTMLNode insertContent = infobox.content;
		HTMLNode insertForm = ctx.addFormChild(insertContent, ContentFilterToadlet.PATH, "filterPreviewForm");
	    insertForm.addChild("#", l10n("filterFileLabel"));
	    insertForm.addChild("br");
	    insertForm.addChild("br");
        
        // apply read filter, write filter, or both
        //TODO: radio buttons to select, once ContentFilter supports write filtering
	    insertForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "hidden", "filter-operation", FilterOperation.BOTH.toString() });

        // display in browser or save to disk
	    insertForm.addChild("input",
                new String[] { "type", "name", "value", "id" },
                new String[] { "radio", "result-handling", ResultHandling.DISPLAY.toString(), "resHandlingDisplay" });
	    insertForm.addChild("label",
                new String[] { "for" },
                new String[] { "resHandlingDisplay" },
                ContentFilterToadlet.l10n("displayResultLabel"));
	    insertForm.addChild("br");
	    insertForm.addChild("input",
                new String[] { "type", "name", "value", "id" },
                new String[] { "radio", "result-handling", ResultHandling.SAVE.toString(), "resHandlingSave" });
	    insertForm.addChild("label",
                new String[] { "for" },
                new String[] { "resHandlingSave" },
                ContentFilterToadlet.l10n("saveResultLabel"));
	    insertForm.addChild("br");
	    insertForm.addChild("br");
        
        // mime type
        insertForm.addChild("#", ContentFilterToadlet.l10n("mimeTypeLabel") + ": ");
        insertForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "text", "mime-type", "" });
        insertForm.addChild("br");
        insertForm.addChild("#", ContentFilterToadlet.l10n("mimeTypeText"));
        insertForm.addChild("br");
        insertForm.addChild("br");
        
		// Local file browser
		if (ctx.isAllowedFullAccess()) {
			insertForm.addChild("#",
			        NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseLabel")+": ");
			insertForm.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "submit", "filter-local",
			                NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseButton") + "..." });
			insertForm.addChild("br");
		}
		insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileLabel") + ": ");
		insertForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
        
	    insertForm.addChild("input",
                new String[] { "type", "name", "value" },
                new String[] { "submit", "filter-upload",
                        ContentFilterToadlet.l10n("filterFileFilterLabel") });
	    return insertBox;
	}
	
	String l10n (String key) {
		return NodeL10n.getBase().getString("FileInsertWizardToadlet."+key);
	}
	
	String l10n (String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FileInsertWizardToadlet."+key, pattern, value);
	}

	@Override
	public boolean isEnabled (ToadletContext ctx) {
		return (!container.publicGatewayMode()) || ((ctx != null) && ctx.isAllowedFullAccess());
	}
}
