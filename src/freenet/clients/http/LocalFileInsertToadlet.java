/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.L10n;
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

	/**
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 */
	public void handleGet(URI uri, HTTPRequest request, ToadletContext toadletContext) throws ToadletContextClosedException, IOException, RedirectException {
		String path = request.getParam("path");
		if (path.length() == 0) {
			if (currentPath == null) {
				currentPath = new File(System.getProperty("user.home"));
			}
			writePermanentRedirect(toadletContext, "Found", "?path=" + URLEncoder.encode(currentPath.getAbsolutePath(),true));
			return;
		}

		currentPath = new File(path).getCanonicalFile();
		
		if(!core.allowUploadFrom(currentPath)) {
			sendErrorPage(toadletContext, 403, "Forbidden", l10n("dirAccessDenied"));
			return;
		}
		
		PageMaker pageMaker = toadletContext.getPageMaker();

		HTMLNode pageNode = pageMaker.getPageNode(l10n("listingTitle", "path", currentPath.getAbsolutePath()), toadletContext);
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		if(toadletContext.isAllowedFullAccess())
			contentNode.addChild(core.alerts.createSummary());
		
		HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
		infoboxDiv.addChild("div", "class", "infobox-header", l10n("listing", "path",  currentPath.getAbsolutePath()));
		HTMLNode listingDiv = infoboxDiv.addChild("div", "class", "infobox-content");

		if (currentPath.exists() && currentPath.isDirectory() && currentPath.canRead()) {
			File[] files = currentPath.listFiles();
			Arrays.sort(files, new Comparator() {

				public int compare(Object first, Object second) {
					File firstFile = (File) first;
					File secondFile = (File) second;
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
				rootLinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentRoot.getCanonicalPath(),false), currentRoot.getCanonicalPath());
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backlinkCellNode = backlinkRow.addChild("td");
				backlinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentPath.getParent(),false), "..");
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					fileRow.addChild("td");
					if (currentFile.canRead()) {
						HTMLNode directoryCellNode = fileRow.addChild("td");
						directoryCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentFile.getAbsolutePath(),false), currentFile.getName());
					} else {
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = toadletContext.addFormChild(cellNode, "/queue/", "insertLocalFileForm"); 
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath() });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local-file", l10n("insert")});
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
			listingDiv.addChild("#", l10n("dirCannotBeRead", "path", currentPath.getAbsolutePath()));
			HTMLNode ulNode = listingDiv.addChild("ul");
			ulNode.addChild("li", l10n("checkPathExist"));
			ulNode.addChild("li", l10n("checkPathIsDir"));
			ulNode.addChild("li", l10n("checkPathReadable"));
		}

		writeHTMLReply(toadletContext, 200, "OK", pageNode.generate());
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("LocalFileInsertToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String msg) {
		return L10n.getString("LocalFileInsertToadlet."+msg);
	}

	/**
	 * @see freenet.clients.http.Toadlet#supportedMethods()
	 */
	public String supportedMethods() {
		return "GET,POST";
	}

}
