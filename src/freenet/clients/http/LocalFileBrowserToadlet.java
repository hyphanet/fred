/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public abstract class LocalFileBrowserToadlet extends Toadlet {	
	private final NodeClientCore core;
	private File currentPath;

	public LocalFileBrowserToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.core = core;
	}
	
	public abstract String path();
	
	protected abstract String postTo();
	
	/**
	 * Performs sanity checks and generates parameter persistence.
	 * Must be called before using createHiddenParamFields or createDirectoryButton
	 * because it returns hiddenFieldName and HiddenFieldValue pairs.
	 */
	protected abstract Hashtable<String, String> persistanceFields(Hashtable<String, String> set);
	
	protected void createInsertDirectoryButton(HTMLNode fileRow, String path, ToadletContext ctx, Hashtable<String, String> fieldPairs) {
		HTMLNode cellNode = fileRow.addChild("td");
		HTMLNode formNode = ctx.addFormChild(cellNode, postTo(), "insertLocalFileForm");
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-dir", l10n("insert")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", path});
		createHiddenParamFields(formNode, fieldPairs);
	}
	
	private final void createChangeDirButton(HTMLNode formNode, String buttonText, String path, Hashtable<String, String> fieldPairs){
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "change-dir", buttonText});
		formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "path", path});
		createHiddenParamFields(formNode, fieldPairs);
	}
	
	/**
	 * 
	 * @param HTTPRequest request
	 * @return Hashtable of all GET params.
	 */
	private final Hashtable<String, String> readGET(HTTPRequest request)
	{
		Hashtable<String, String> set = new Hashtable<String, String>();
		Collection<String> names = request.getParameterNames();
		for(String key : names)
		{
			set.put(key, request.getParam(key));
		}
		return set;
	}
	
	/**
	 * If the name is not recognized, the value may have a maximum length
	 * of 4096 characters. "key" and "overrideSplitfileKey" have a maximum
	 * length of QueueToadlet.MAX_KEY_LENGTH, and "message" has a maximum
	 * length of 5*1024.
	 * @param HTTPRequest request
	 * @return Hashtable of all POST parts.
	 */
	protected Hashtable<String, String> readPOST(HTTPRequest request)
	{
		Hashtable<String, String> set = new Hashtable<String, String>();
		String[] names = request.getParts();
		for(String key : names)
		{
			if(key.equals("key") || key.equals("overrideSplitfileKey")) {
				set.put(key, request.getPartAsStringFailsafe(key, QueueToadlet.MAX_KEY_LENGTH));
			}
			else if(key.equals("message")){
				set.put(key, request.getPartAsStringFailsafe(key, 5*1024));
			}
			else {
				//TODO: What is an appropriate maximum length?
				set.put(key, request.getPartAsStringFailsafe(key, 4096));
			}
		}
		return set;
	}
	
	/**
	 * Renders hidden fields on given formNode. 
	 */
	private final void createHiddenParamFields(HTMLNode formNode, Hashtable<String, String> fieldPairs){
		Enumeration<String> keys = fieldPairs.keys();
		String key;
		while(keys.hasMoreElements())
		{
			key = keys.nextElement();
			formNode.addChild("input", new String[] { "type", "name", "value" }, 
					new String[] { "hidden", key, fieldPairs.get(key)});
		}
		return;
	}

	// FIXME reentrancy issues with currentPath - fix running two at once.
	/**
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 */
	public void handleMethodGET(URI uri, HTTPRequest request, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		renderPage(persistanceFields(readGET(request)), request.getParam("path"), ctx);
	}
	public void handleMethodPOST(URI uri, HTTPRequest request, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		renderPage(persistanceFields(readPOST(request)), request.getPartAsStringFailsafe("path", 4096), ctx);
	}
	
	private void renderPage(Hashtable<String, String> fieldPairs, String path, final ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if (path.length() == 0) {
			if (currentPath == null) {
				currentPath = new File(System.getProperty("user.home")); // FIXME what if user.home is denied?
			}
			path = currentPath.getCanonicalPath();
		}

		File thisPath = new File(path).getCanonicalFile();
		
		PageMaker pageMaker = ctx.getPageMaker();

		if(!core.allowUploadFrom(thisPath)) {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", thisPath.getAbsolutePath()), ctx);
			pageMaker.getInfobox("infobox-error",  "Forbidden", page.content, "access-denied", true).
				addChild("#", l10n("dirAccessDenied"));

			thisPath = currentPath;
			if(!core.allowUploadFrom(thisPath)) {
				File[] allowedDirs = core.getAllowedUploadDirs();
				if(allowedDirs.length == 0) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				} else {
					thisPath = allowedDirs[core.node.fastWeakRandom.nextInt(allowedDirs.length)];
				}
			}
		}
		
		if(currentPath == null)
			currentPath = thisPath;
		
		HTMLNode pageNode;

		if (thisPath.exists() && thisPath.isDirectory() && thisPath.canRead()) {
			// Known safe at this point
			currentPath = thisPath;

			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");
			
			File[] files = currentPath.listFiles();
			
			if(files == null) {
				File home = new File(System.getProperty("user.home")); // FIXME what if user.home is denied?
				if(home.equals(currentPath)) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				}
				sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
				currentPath = home;
				renderPage(fieldPairs, path, ctx);
				return;
			}
			
			Arrays.sort(files, new Comparator<File>() {
				public int compare(File firstFile, File secondFile) {
					if (firstFile.isDirectory() && !secondFile.isDirectory()) {
						return -1;
					}
					if (!firstFile.isDirectory() && secondFile.isDirectory()) {
						return 1;
					}
					return firstFile.getName().compareToIgnoreCase(secondFile.getName());
				}
			});
			HTMLNode listingTable = listingDiv.addChild("table");
			HTMLNode headerRow = listingTable.addChild("tr");
			headerRow.addChild("th");
			headerRow.addChild("th", l10n("fileHeader"));
			headerRow.addChild("th", l10n("sizeHeader"));
			/* add filesystem roots (fsck windows) */
			File[] roots = File.listRoots();
			for (int rootIndex = 0, rootCount = roots.length; rootIndex < rootCount; rootIndex++) {
				File currentRoot = roots[rootIndex];
				HTMLNode rootRow = listingTable.addChild("tr");
				rootRow.addChild("td");
				HTMLNode rootLinkCellNode = rootRow.addChild("td");
				HTMLNode rootLinkFormNode = ctx.addFormChild(rootLinkCellNode, path(), "insertLocalFileForm");
				createChangeDirButton(rootLinkFormNode, currentRoot.getCanonicalPath(), currentRoot.getAbsolutePath(), fieldPairs);
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backLinkCellNode = backlinkRow.addChild("td");
				HTMLNode backLinkFormNode = ctx.addFormChild(backLinkCellNode, path(), "insertLocalFileForm");
				createChangeDirButton(backLinkFormNode, "..", currentPath.getParent(), fieldPairs);
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					if (currentFile.canRead()) {
						createInsertDirectoryButton(fileRow, currentFile.getAbsolutePath(), ctx, fieldPairs);
						
						// Change directory
						HTMLNode directoryCellNode = fileRow.addChild("td");
						HTMLNode directoryFormNode = ctx.addFormChild(directoryCellNode, path(), "insertLocalFileForm");
						createChangeDirButton(directoryFormNode, currentFile.getName(), currentFile.getAbsolutePath(), fieldPairs);
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						
						// Insert file
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = ctx.addFormChild(cellNode, postTo(), "insertLocalFileForm");
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-file", l10n("insert")});
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath()});
						createHiddenParamFields(formNode, fieldPairs);
						
						fileRow.addChild("td", currentFile.getName());
						fileRow.addChild("td", "class", "right-align", String.valueOf(currentFile.length()));
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
						fileRow.addChild("td", "class", "right-align", String.valueOf(currentFile.length()));
					}
				}
			}
		} else {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(ctx.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");

			listingDiv.addChild("#", l10n("dirCannotBeRead", "path", currentPath.getAbsolutePath()));
			HTMLNode ulNode = listingDiv.addChild("ul");
			ulNode.addChild("li", l10n("checkPathExist"));
			ulNode.addChild("li", l10n("checkPathIsDir"));
			ulNode.addChild("li", l10n("checkPathReadable"));
		}
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String msg) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+msg);
	}
}
