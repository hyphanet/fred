/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Hashtable;

/**
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public abstract class LocalFileBrowserToadlet extends Toadlet {	
	protected final NodeClientCore core;
	private File currentPath;

	public LocalFileBrowserToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.core = core;
	}
	
	public abstract String path();
	
	protected abstract String postTo();
	
	/**
	 * Performs sanity checks and generates parameter persistence fields.
	 * @param set page parts/parameters
	 * @return fieldPairs correct fields to persist for this browser type
	 */
	protected abstract Hashtable<String, String> persistenceFields (Hashtable<String, String> set);
	
	protected void createSelectDirectoryButton (HTMLNode node, String absolutePath) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "select-dir", l10n("insert")});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "filename", absolutePath});
	}
	
	protected void createSelectFileButton (HTMLNode node, String absolutePath) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "select-file", l10n("insert")});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "filename", absolutePath});
	}

	private void createChangeDirButton (HTMLNode node, String buttonText, String path) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "change-dir", buttonText});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "path", path});
	}
	
	/**
	 * Returns a Hashtable of all URL parameters.
	 * @param request contains URL parameters
	 * @return Hashtable of all GET params.
	 */
	private Hashtable<String, String> readGET (HTTPRequest request) {
		Hashtable<String, String> set = new Hashtable<String, String>();
		for (String key : request.getParameterNames()) {
			set.put(key, request.getParam(key));
		}
		return set;
	}
	
	/**
	 * Returns a Hashtable of all POST parts up to a length of 1024*1024 characters.
	 * @param request contains POST parts
	 * @return set a Hashtable of all POST parts.
	 */
	private Hashtable<String, String> readPOST (HTTPRequest request) {
		Hashtable<String, String> set = new Hashtable<String, String>();
		for (String key : request.getParts()) {
			set.put(key, request.getPartAsStringFailsafe(key, 1024*1024));
		}
		return set;
	}
	
	/**
	 * Renders hidden fields.
	 * @param fieldPairs Pairs of values to be rendered
	 * @return result HTMLNode containing hidden persistence fields
	 */
	private HTMLNode renderPersistenceFields (Hashtable<String, String> fieldPairs) {
		HTMLNode result = new HTMLNode("div", "id", "persistenceFields");
		for (String key : fieldPairs.keySet()) {
			result.addChild("input", 
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", key, fieldPairs.get(key)});
		}
		return result;
	}

	// FIXME reentrancy issues with currentPath - fix running two at once.
	/**
	 * @param uri is unused,
	 * @param request contains parameters.
	 * @param ctx allows page rendering and permissions checks.
	 * @exception ToadletContextClosedException Access is denied: uploading might be disabled overall.
	 *                                                            The user might be denied access to this directory,
	 *                                                            which could be their home directory.
	 * @exception IOException Something file-related went wrong.
	 * @see <a href="freenet/clients/http/Toadlet#findSupportedMethods()">findSupportedMethods</a>
	 * @see "java.net.URI"
	 * @see "<a href="freenet/clients/http/ToadletContext.html">ToadletContext</a>
	 */
	public void handleMethodGET (URI uri, HTTPRequest request, final ToadletContext ctx)
	        throws ToadletContextClosedException, IOException {
		renderPage(persistenceFields(readGET(request)), request.getParam("path"), ctx);
	}

	public void handleMethodPOST (URI uri, HTTPRequest request, final ToadletContext ctx)
	        throws ToadletContextClosedException, IOException {
		renderPage(persistenceFields(readPOST(request)), request.getPartAsStringFailsafe("path", 4096), ctx);
	}
	
	private void renderPage (Hashtable<String, String> fieldPairs, String path, final ToadletContext ctx)
	        throws ToadletContextClosedException, IOException {
		HTMLNode persistenceFields = renderPersistenceFields(fieldPairs);
		if (path.length() == 0) {
			if (currentPath == null) {
				// FIXME what if user.home is denied?
				currentPath = new File(System.getProperty("user.home"));
			}
			path = currentPath.getCanonicalPath();
		}

		File thisPath = new File(path).getCanonicalFile();

		PageMaker pageMaker = ctx.getPageMaker();

		if (!core.allowUploadFrom(thisPath)) {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", thisPath.getAbsolutePath()),
			        ctx);
			pageMaker.getInfobox("infobox-error",  "Forbidden", page.content, "access-denied", true).
			        addChild("#", l10n("dirAccessDenied"));

			thisPath = currentPath;
			if (!core.allowUploadFrom(thisPath)) {
				File[] allowedDirs = core.getAllowedUploadDirs();
				if (allowedDirs.length == 0) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				} else {
					thisPath = allowedDirs[core.node.fastWeakRandom.nextInt(allowedDirs.length)];
				}
			}
		}
		
		if (currentPath == null) currentPath = thisPath;
		
		HTMLNode pageNode;

		if (thisPath.exists() && thisPath.isDirectory() && thisPath.canRead()) {
			// Known safe at this point
			currentPath = thisPath;

			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path",
			        currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if (ctx.isAllowedFullAccess()) contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",
			        currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");
			
			File[] files = currentPath.listFiles();
			
			if (files == null) {
				// FIXME what if user.home is denied?
				File home = new File(System.getProperty("user.home"));
				if (home.equals(currentPath)) {
					sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				}
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
				HTMLNode rootLinkFormNode = ctx.addFormChild(rootLinkCellNode, path(),
				        "insertLocalFileForm");
				createChangeDirButton(rootLinkFormNode, currentRoot.getCanonicalPath(),
				        currentRoot.getAbsolutePath());
				rootLinkFormNode.addChild(persistenceFields);
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backLinkCellNode = backlinkRow.addChild("td");
				HTMLNode backLinkFormNode = ctx.addFormChild(backLinkCellNode, path(),
				        "insertLocalFileForm");
				createChangeDirButton(backLinkFormNode, "..", currentPath.getParent());
				backLinkFormNode.addChild(persistenceFields);
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					if (currentFile.canRead()) {
						// Select directory
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = ctx.addFormChild(cellNode, postTo(),
						        "insertLocalFileForm");
						createSelectDirectoryButton(formNode, currentFile.getAbsolutePath());
						formNode.addChild(persistenceFields);

						// Change directory
						HTMLNode directoryCellNode = fileRow.addChild("td");
						HTMLNode directoryFormNode = ctx.addFormChild(directoryCellNode, path(),
						        "insertLocalFileForm");
						createChangeDirButton(directoryFormNode, currentFile.getName(),
						        currentFile.getAbsolutePath());
						directoryFormNode.addChild(persistenceFields);
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file",
						        currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						//Select file
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = ctx.addFormChild(cellNode, postTo(),
						        "insertLocalFileForm");
						createSelectFileButton(formNode, currentFile.getAbsolutePath());
						formNode.addChild(persistenceFields);
						
						fileRow.addChild("td", currentFile.getName());
						fileRow.addChild("td", "class", "right-align",
						        String.valueOf(currentFile.length()));
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file",
						        currentFile.getName());
						fileRow.addChild("td", "class", "right-align",
						        String.valueOf(currentFile.length()));
					}
				}
			}
		} else {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path",
			        currentPath.getAbsolutePath()), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if (ctx.isAllowedFullAccess()) contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",
			        currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");

			listingDiv.addChild("#", l10n("dirCannotBeRead", "path", currentPath.getAbsolutePath()));
			HTMLNode ulNode = listingDiv.addChild("ul");
			ulNode.addChild("li", l10n("checkPathExist"));
			ulNode.addChild("li", l10n("checkPathIsDir"));
			ulNode.addChild("li", l10n("checkPathReadable"));
		}
		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private String l10n (String key, String pattern, String value) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+key,
		        new String[] { pattern },
		        new String[] { value });
	}

	private String l10n(String msg) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+msg);
	}
}
