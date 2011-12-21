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

	public LocalFileBrowserToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.core = core;
	}
	
	@Override
	public abstract String path();
	
	protected abstract String postTo();

	/**
	 * Whether the directory is allowed for the purposes of the specific browser. For example, do the node settings
	 * allow downloading to the given directory?
	 * @param path The path to check permissions for.
	 * @return Whether browsing that directory is allowed. If it is not, the LocalFileBrowserToadlet will not render
	 * a selection button or directory-changing button for it.
	 */
	protected abstract boolean allowedDir(File path);
	
	/**
	 * Performs sanity checks and generates parameter persistence fields.
	 * @param set page parts/parameters
	 * @return fieldPairs correct fields to persist for this browser type
	 */
	protected abstract Hashtable<String, String> persistenceFields (Hashtable<String, String> set);

	/**
	 * Determines the appropriate directory to start out in for the given browser. If a path is not already
	 * specified, the browser will attempt to display this directory.
	 * @return path to directory
	 */
	protected abstract String startingDir();

	/**
	 * Determines the appropriate directory to start out in if browsing for something to upload.
	 * @return If all directories or no directories are allowed, returns the user's home directory.
	 * Otherwise, returns the first allowed directory.
	 */
	protected String defaultUploadDir() {
		if ((core.getAllowedUploadDirs().length == 1 && core.getAllowedUploadDirs()[0].toString().equals("all"))
		        || core.getAllowedUploadDirs().length == 0) {
			/* If all directories are allowed, or none are, go for the home directory.
			 * If none are allowed, any directory will result in an error anyway.
			 */
			return System.getProperty("user.home");
		}
		//If locations are explicitly specified take the first one.
		return core.getAllowedUploadDirs()[0].getAbsolutePath();
	}

	/**
	 * Determines the appropriate directory to start out in if browsing for something to download.
	 * @return If all directories or no directories are allowed, the default downloads directory is returned.
	 * Otherwise, returns the first allowed directory.
	 */
	protected String defaultDownloadDir() {
		if ((core.getAllowedDownloadDirs().length == 1 && core.getAllowedDownloadDirs()[0].toString().equals("all"))
		        || core.getAllowedDownloadDirs().length == 0) {
			/* If all directories are allowed, or none are, go for the default download directory.
			 * If none are allowed, any directory will result in an error anyway.
			 */
			return core.getDownloadsDir().getAbsolutePath();
		}
		//If locations are explicitly specified take the first one.
		return core.getAllowedDownloadDirs()[0].getAbsolutePath();
	}
	
	protected void createSelectDirectoryButton (HTMLNode node, String absolutePath, HTMLNode persistence) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "select-dir", l10n("insert")});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "filename", absolutePath});
		node.addChild(persistence);
	}
	
	protected void createSelectFileButton (HTMLNode node, String absolutePath, HTMLNode persistence) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "select-file", l10n("insert")});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "filename", absolutePath});
		node.addChild(persistence);
	}

	private void createChangeDirButton (HTMLNode node, String buttonText, String path, HTMLNode persistence) {
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"submit", "change-dir", buttonText});
		node.addChild("input",
		        new String[]{"type", "name", "value"},
		        new String[]{"hidden", "path", path});
		node.addChild(persistence);
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
			//Path not specified, fall back to default.
			path = startingDir();
		}

		File currentPath = new File(path).getCanonicalFile();
		//For use in error messages.
		String attemptedPath = currentPath == null ? "null" : currentPath.getAbsolutePath();

		PageMaker pageMaker = ctx.getPageMaker();

		if (currentPath != null && !allowedDir(currentPath)) {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", attemptedPath), ctx);
			pageMaker.getInfobox("infobox-error",  "Forbidden", page.content, "access-denied", true).
			        addChild("#", l10n("dirAccessDenied"));

			sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
			return;
		}
		
		HTMLNode pageNode;

		if (currentPath != null && currentPath.exists() && currentPath.isDirectory() && currentPath.canRead()) {
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
				sendErrorPage(ctx, 403, "Forbidden", l10n("dirAccessDenied"));
				return;
			}
			
			Arrays.sort(files, new Comparator<File>() {
				@Override
				public int compare(File firstFile, File secondFile) {
					/* Put directories above files, sorting each alphabetically and
					 * case-insensitively.
					 */
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
			for (File currentRoot : File.listRoots()) {
				if (allowedDir(currentRoot)) {
				HTMLNode rootRow = listingTable.addChild("tr");
				rootRow.addChild("td");
				HTMLNode rootLinkCellNode = rootRow.addChild("td");
				HTMLNode rootLinkFormNode = ctx.addFormChild(rootLinkCellNode, path(),
				        "insertLocalFileForm");
					createChangeDirButton(rootLinkFormNode, currentRoot.getCanonicalPath(),
					        currentRoot.getAbsolutePath(), persistenceFields);
				rootRow.addChild("td");
				}
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				if (allowedDir(currentPath.getParentFile())) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backLinkCellNode = backlinkRow.addChild("td");
				HTMLNode backLinkFormNode = ctx.addFormChild(backLinkCellNode, path(),
				        "insertLocalFileForm");
					createChangeDirButton(backLinkFormNode, "..", currentPath.getParent(), persistenceFields);
				backlinkRow.addChild("td");
				}
			}
			for (File currentFile : files) {
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					if (currentFile.canRead()) {
						// Select directory
						if (allowedDir(currentFile)) {
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = ctx.addFormChild(cellNode, postTo(),
						        "insertLocalFileForm");

							createSelectDirectoryButton(formNode, currentFile.getAbsolutePath(),
							        persistenceFields);

						// Change directory
						HTMLNode directoryCellNode = fileRow.addChild("td");
						HTMLNode directoryFormNode = ctx.addFormChild(directoryCellNode, path(),
						        "insertLocalFileForm");
							createChangeDirButton(directoryFormNode, currentFile.getName(),
							        currentFile.getAbsolutePath(), persistenceFields);
						}
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
						createSelectFileButton(formNode, currentFile.getAbsolutePath(),
						        persistenceFields);
						
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
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", attemptedPath), ctx);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if (ctx.isAllowedFullAccess()) contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path", attemptedPath));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");

			listingDiv.addChild("#", l10n("dirCannotBeRead", "path", attemptedPath));
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
