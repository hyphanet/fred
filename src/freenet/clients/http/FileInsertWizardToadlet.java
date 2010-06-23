package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class FileInsertWizardToadlet extends Toadlet implements LinkEnabledCallback {

	protected FileInsertWizardToadlet(HighLevelSimpleClient client, NodeClientCore clientCore) {
		super(client);
		this.core = clientCore;
	}

	final NodeClientCore core;
	
	static final String PATH = "/insertfile/";
	
	@Override
	public String path() {
		return PATH;
	}
	
	public void handleMethodGET(URI uri, final HTTPRequest request, final ToadletContext ctx) 
	throws ToadletContextClosedException, IOException, RedirectException {

//		// We ensure that we have a FCP server running
//		if(!fcp.enabled){
//			writeError(NodeL10n.getBase().getString("QueueToadlet.fcpIsMissing"), NodeL10n.getBase().getString("QueueToadlet.pleaseEnableFCP"), ctx, false);
//			return;
//		}
//		
//		if(!core.hasLoadedQueue()) {
//			writeError(NodeL10n.getBase().getString("QueueToadlet.notLoadedYetTitle"), NodeL10n.getBase().getString("QueueToadlet.notLoadedYet"), ctx, false);
//			return;
//		}
//		
		if(container.publicGatewayMode() && !ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, NodeL10n.getBase().getString("Toadlet.unauthorizedTitle"), NodeL10n.getBase().getString("Toadlet.unauthorized"));
			return;
		}
		
		final PageMaker pageMaker = ctx.getPageMaker();
		
		final int mode = pageMaker.parseMode(request, this.container);
		
		PageNode page = pageMaker.getPageNode(l10n("pageTitle"), ctx);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		/* add alert summary box */
		if(ctx.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());

		pageNode.addChild(createInsertBox(pageMaker, ctx, mode >= PageMaker.MODE_ADVANCED));
		
		writeHTMLReply(ctx, 200, "OK", null, pageNode.generate());
	}
	
	private HTMLNode createInsertBox(PageMaker pageMaker, ToadletContext ctx, boolean isAdvancedModeEnabled) {
		/* the insert file box */
		InfoboxNode infobox = pageMaker.getInfobox(NodeL10n.getBase().getString("QueueToadlet.insertFile"), "insert-queue", true);
		HTMLNode insertBox = infobox.outer;
		HTMLNode insertContent = infobox.content;
		HTMLNode insertForm = ctx.addFormChild(insertContent, QueueToadlet.PATH_UPLOADS, "queueInsertForm");
		insertForm.addChild("#", (NodeL10n.getBase().getString("QueueToadlet.insertAs") + ' '));
		insertForm.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", "keytype", "chk", "checked" });
		insertForm.addChild("#", " CHK \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", "keytype", "ksk" });
		insertForm.addChild("#", " KSK/SSK/USK \u00a0");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "text", "key", "KSK@" });
		if(isAdvancedModeEnabled) {
			insertForm.addChild("#", " \u00a0 ");
			insertForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "compress", "checked" });
			insertForm.addChild("#", ' ' + NodeL10n.getBase().getString("QueueToadlet.insertFileCompressLabel") + " \u00a0 ");
		} else {
			insertForm.addChild("input", new String[] { "type", "value" }, new String[] { "hidden", "true" });
		}
		insertForm.addChild("input", new String[] { "type", "name" }, new String[] { "reset", NodeL10n.getBase().getString("QueueToadlet.insertFileResetForm") });
		if(ctx.isAllowedFullAccess()) {
			insertForm.addChild("br");
			insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseLabel")+": ");
			insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local", NodeL10n.getBase().getString("QueueToadlet.insertFileBrowseButton") + "..." });
			insertForm.addChild("br");
		}
		if(isAdvancedModeEnabled) {
			insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.compatModeLabel")+": ");
			HTMLNode select = insertForm.addChild("select", "name", "compatibilityMode");
			for(CompatibilityMode mode : InsertContext.CompatibilityMode.values()) {
				if(mode == CompatibilityMode.COMPAT_UNKNOWN) continue;
				// FIXME l10n???
				HTMLNode option = select.addChild("option", "value", mode.name(), mode.detail);
				if(mode == CompatibilityMode.COMPAT_CURRENT)
					option.addAttribute("selected", "");
			}
		}
		insertForm.addChild("#", NodeL10n.getBase().getString("QueueToadlet.insertFileLabel") + ": ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "file", "filename", "" });
		insertForm.addChild("#", " \u00a0 ");
		insertForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert", NodeL10n.getBase().getString("QueueToadlet.insertFileInsertFileLabel") });
		insertForm.addChild("#", " \u00a0 ");
		return insertBox;
	}
	
	String l10n(String key) {
		return NodeL10n.getBase().getString("FileInsertWizardToadlet."+key);
	}
	
	String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FileInsertWizardToadlet."+key, pattern, value);
	}

	public boolean isEnabled(ToadletContext ctx) {
		return (!container.publicGatewayMode()) || ((ctx != null) && ctx.isAllowedFullAccess());
	}



}
