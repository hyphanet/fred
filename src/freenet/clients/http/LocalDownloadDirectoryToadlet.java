package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

import java.util.Hashtable;

public class LocalDownloadDirectoryToadlet extends LocalDirectoryToadlet {

	LocalDownloadDirectoryToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String postTo) {
		super(core, highLevelSimpleClient, postTo);
	}

	@Override
	protected void createSelectDirectoryButton(HTMLNode formNode, String path) {
		formNode.addChild("input", new String[] { "type", "name", "value" },
				new String[] { "submit", "insert", NodeL10n.getBase().getString("QueueToadlet.download")});
		formNode.addChild("input", new String[] { "type", "name", "value" },
				new String[] { "hidden", "path", path});
	}

	@Override
	protected Hashtable<String, String> persistenceFields(Hashtable<String, String> set) {
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		for(String key : set.keySet()) {
			if (key.equals("bulkDownloads") || key.equals("filterData")) {
				fieldPairs.put(key, set.get(key));
			}
		}
		fieldPairs.put("target", "disk");
		return fieldPairs;
	}
}
