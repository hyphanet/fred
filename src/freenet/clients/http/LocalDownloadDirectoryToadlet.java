package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

import java.io.File;
import java.util.Hashtable;

public class LocalDownloadDirectoryToadlet extends LocalDirectoryToadlet {

	LocalDownloadDirectoryToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String post) {
		super(core, highLevelSimpleClient, post);
	}

	@Override
	protected String startingDir() {
		return defaultDownloadDir();
	}

	@Override
	protected boolean allowedDir(File path) {
		return core.allowDownloadTo(path);
	}

	@Override
	protected String filenameField() {
		return "path";
	}

	@Override
	protected void createSelectDirectoryButton (HTMLNode formNode, String path, HTMLNode persist) {
		formNode.addChild("input",
			new String[] { "type", "name", "value" },
			new String[] { "submit", selectDir,
				NodeL10n.getBase().getString("QueueToadlet.download")});
		formNode.addChild("input",
			new String[] { "type", "name", "value" },
			new String[] { "hidden", filenameField(), path});
		formNode.addChild(persist);
	}

	@Override
	protected Hashtable<String, String> persistenceFields (Hashtable<String, String> set) {
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		//From bulk downloads, set download button.
		if (set.containsKey("bulkDownloads")) {
			fieldPairs.put("bulkDownloads", set.get("bulkDownloads"));
			fieldPairs.put("insert", "1");
			fieldPairs.put("target", "disk");
		//From FProxy page, set download button.
		} else if (set.containsKey("key")) {
			fieldPairs.put("key", set.get("key"));
			fieldPairs.put("download", "1");
			fieldPairs.put("return-type", "disk");
		}

		if (set.containsKey("filterData")) fieldPairs.put("filterData", set.get("filterData"));
		return fieldPairs;
	}
}
