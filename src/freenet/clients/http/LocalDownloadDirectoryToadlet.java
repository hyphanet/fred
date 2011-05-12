package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

import java.util.Hashtable;

public class LocalDownloadDirectoryToadlet extends LocalDirectoryToadlet {

	LocalDownloadDirectoryToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String post) {
		super(core, highLevelSimpleClient, post);
	}

	@Override
	protected void createSelectDirectoryButton (HTMLNode formNode, String path) {
		if (core.allowDownloadTo(new java.io.File(path))) {
			formNode.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "submit", "select-dir",
			                NodeL10n.getBase().getString("QueueToadlet.download")});
			formNode.addChild("input",
			        new String[] { "type", "name", "value" },
			        new String[] { "hidden", "path", path});
		}
	}

	@Override
	protected Hashtable<String, String> persistenceFields (Hashtable<String, String> set) {
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		for(String key : set.keySet()) {
			if(key.equals("bulkDownloads") || key.equals("filterData") || key.equals("key")) {
				fieldPairs.put(key, set.get(key));
				//From FProxy page, set download button.
				if(key.equals("key")) {
					fieldPairs.put("download", "1");
					fieldPairs.put("return-type", "disk");
				//From bulk downloads, set download button.
				} else if(key.equals("bulkDownloads")) {
					fieldPairs.put("insert", "1");
					fieldPairs.put("target", "disk");
				}
			}
		}
		return fieldPairs;
	}
}
