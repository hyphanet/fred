package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

import freenet.client.HighLevelSimpleClient;
import freenet.node.Node;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.URLEncoder;

/**
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class LocalFileInsertToadlet extends Toadlet {

	private final Node node;

	private File currentPath;

	/**
	 * 
	 */
	public LocalFileInsertToadlet(Node node, HighLevelSimpleClient highLevelSimpleClient) {
		super(highLevelSimpleClient);
		this.node = node;
	}

	/**
	 * @see freenet.clients.http.Toadlet#handleGet(java.net.URI,
	 *      freenet.clients.http.ToadletContext)
	 */
	public void handleGet(URI uri, ToadletContext toadletContext) throws ToadletContextClosedException, IOException, RedirectException {
		HTTPRequest request = new HTTPRequest(uri, null, toadletContext);

		String path = request.getParam("path");
		if (path.length() == 0) {
			if (currentPath == null) {
				currentPath = new File(System.getProperty("user.dir"));
			}
			writePermanentRedirect(toadletContext, "Found", "?path=" + URLEncoder.encode(currentPath.getAbsolutePath()));
			return;
		}

		currentPath = new File(path).getCanonicalFile();

		StringBuffer pageBuffer = new StringBuffer(16384);
		PageMaker pageMaker = toadletContext.getPageMaker();

		HTMLNode pageNode = pageMaker.getPageNode("Listing of " + currentPath.getAbsolutePath());
		HTMLNode contentNode = pageMaker.getContentNode(pageNode);
		contentNode.addChild(node.alerts.createSummary());
		
		HTMLNode infoboxDiv = contentNode.addChild("div", "class", "infobox");
		infoboxDiv.addChild("div", "class", "infobox-header", "Directory Listing: " + currentPath.getAbsolutePath());
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
			headerRow.addChild("th", "File");
			headerRow.addChild("th", "Size");
			/* add filesystem roots (fsck windows) */
			File[] roots = File.listRoots();
			for (int rootIndex = 0, rootCount = roots.length; rootIndex < rootCount; rootIndex++) {
				File currentRoot = roots[rootIndex];
				HTMLNode rootRow = listingTable.addChild("tr");
				rootRow.addChild("td");
				HTMLNode rootLinkCellNode = rootRow.addChild("td");
				rootLinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentRoot.getCanonicalPath()), currentRoot.getCanonicalPath());
				rootRow.addChild("td");
			}
			/* add back link */
			if (currentPath.getParent() != null) {
				HTMLNode backlinkRow = listingTable.addChild("tr");
				backlinkRow.addChild("td");
				HTMLNode backlinkCellNode = backlinkRow.addChild("td");
				backlinkCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentPath.getParent()), "..");
				backlinkRow.addChild("td");
			}
			for (int fileIndex = 0, fileCount = files.length; fileIndex < fileCount; fileIndex++) {
				File currentFile = files[fileIndex];
				HTMLNode fileRow = listingTable.addChild("tr");
				if (currentFile.isDirectory()) {
					fileRow.addChild("td");
					if (currentFile.canRead()) {
						HTMLNode directoryCellNode = fileRow.addChild("td");
						directoryCellNode.addChild("a", "href", "?path=" + URLEncoder.encode(currentFile.getAbsolutePath()), currentFile.getName());
					} else {
						fileRow.addChild("td", "class", "unreadable-file", currentFile.getName());
					}
					fileRow.addChild("td");
				} else {
					if (currentFile.canRead()) {
						HTMLNode cellNode = fileRow.addChild("td");
						HTMLNode formNode = cellNode.addChild("form", new String[] { "action", "method", "accept-charset" }, new String[] { "/queue/", "post", "utf-8" });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", node.formPassword });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "filename", currentFile.getAbsolutePath() });
						formNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "insert-local", "Insert" });
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
			listingDiv.addChild("#", "The directory \u201c" + currentPath.getAbsolutePath() + "\u201d can not be read.");
			HTMLNode ulNode = listingDiv.addChild("ul");
			ulNode.addChild("li", "Check that the specified path does exist.");
			ulNode.addChild("li", "Check that the specified path is a directory.");
			ulNode.addChild("li", "Check that the specified path is readable by the user running the node.");
		}

		pageNode.generate(pageBuffer);
		writeReply(toadletContext, 200, "text/html; charset=utf-8", "OK", pageBuffer.toString());
	}

	/**
	 * @see freenet.clients.http.Toadlet#supportedMethods()
	 */
	public String supportedMethods() {
		return "GET,POST";
	}

}
