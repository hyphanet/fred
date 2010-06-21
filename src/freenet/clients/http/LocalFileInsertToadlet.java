/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.URLEncoder;
import freenet.support.api.HTTPRequest;

/**
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class LocalFileInsertToadlet extends Toadlet {

	private final NodeClientCore core;

	private File currentPath;

	/**
	 * 
	 */
	public LocalFileInsertToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.core = core;
	}

	// FIXME reentrancy issues with currentPath - fix running two at once.
	/**
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 */
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext toadletContext) throws ToadletContextClosedException, IOException, RedirectException {
		FreenetURI furi = null;
		String key = request.getParam("key");
		if(key != null) {
			try {
				furi = new FreenetURI(key);
			} catch (MalformedURLException e) {
				furi = null;
			}
		}
		String extra = "";
		if(furi != null)
			extra = "&key="+furi.toASCIIString();
		
		File thisPath;

		boolean compress = Boolean.valueOf(request.getParam("compress"));
		extra += "&compress=" + compress;
		
		final String compatibilityMode = request.getParam("compatibilityMode");
		extra += "&compatibilityMode=" + compatibilityMode;

		String path = request.getParam("path");
		if (path.length() == 0) {
			if (currentPath == null) {
				currentPath = new File(System.getProperty("user.home")); // FIXME what if user.home is denied?
			}
			writePermanentRedirect(toadletContext, "Found", "?path=" + URLEncoder.encode(currentPath.getAbsolutePath(),true)+extra);
			return;
		}

		thisPath = new File(path).getCanonicalFile();
		
		
		PageMaker pageMaker = toadletContext.getPageMaker();

		if(!core.allowUploadFrom(thisPath)) {
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", thisPath.getAbsolutePath()), toadletContext);
			pageMaker.getInfobox("infobox-error",  "Forbidden", page.content, "access-denied", true).
				addChild("#", l10n("dirAccessDenied"));

			thisPath = currentPath;
			if(!core.allowUploadFrom(thisPath)) {
				File[] allowedDirs = core.getAllowedUploadDirs();
				if(allowedDirs.length == 0) {
					sendErrorPage(toadletContext, 403, "Forbidden", l10n("dirAccessDenied"));
					return;
				} else {
					thisPath = allowedDirs[core.node.fastWeakRandom.nextInt(allowedDirs.length)];
				}
			}
		}
		
		if(currentPath == null)
			currentPath = thisPath;
		
		HTMLNode pageNode;

		if (currentPath.exists() && currentPath.isDirectory() && currentPath.canRead()) {
			// Known safe at this point
			currentPath = thisPath;

			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), toadletContext);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(toadletContext.isAllowedFullAccess())
				contentNode.addChild(core.alerts.createSummary());
			
			HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
			infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
			HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");


			
			File[] files = currentPath.listFiles();
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
				rootLinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentRoot.getCanonicalPath(),false)+extra, currentRoot.getCanonicalPath());
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backlinkCellNode = backlinkRow.addChild("td");
				backlinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentPath.getParent(),false)+extra, "..");
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					if (currentFile.canRead()) {
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = toadletContext.addFormChild(cellNode, "/uploads/", "insertLocalFileForm");
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath() });
						if (compress) {
							formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "compress", String.valueOf(compress) });
						}
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-dir", l10n("insert")});
						if(furi != null)
							formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", furi.toASCIIString() });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "compatibilityMode", compatibilityMode });
						HTMLNode directoryCellNode = fileRow.addChild("td");
						directoryCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentFile.getAbsolutePath(),false)+extra, currentFile.getName());
					} else {
						fileRow.addChild("td");
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = toadletContext.addFormChild(cellNode, "/uploads/", "insertLocalFileForm");
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath() });
						if (compress) {
							formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "compress", String.valueOf(compress) });
						}
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-file", l10n("insert")});
						if(furi != null)
							formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", furi.toASCIIString() });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "compatibilityMode", compatibilityMode });
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
			PageNode page = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), toadletContext);
			pageNode = page.outer;
			HTMLNode contentNode = page.content;
			if(toadletContext.isAllowedFullAccess())
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

		writeHTMLReply(toadletContext, 200, "OK", pageNode.generate());
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String msg) {
		return NodeL10n.getBase().getString("LocalFileInsertToadlet."+msg);
	}

	@Override
	public String path() {
		return "/files/";
	}

}
