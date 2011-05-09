package freenet.clients.http;

import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

public class LocalDirectoryConfigToadlet extends LocalDirectoryToadlet {

	public LocalDirectoryConfigToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient,
	        String postTo) {
		super(core, highLevelSimpleClient, postTo);
	}
	
	@Override
	protected void createSelectDirectoryButton (HTMLNode formNode, String path) {
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
		        new String[] { "submit", "select-dir",
		                NodeL10n.getBase().getString("ConfigToadlet.selectDirectory")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
		        new String[] { "hidden", "filename", path});
	}

	@Override
	protected  Hashtable<String, String> persistenceFields (Hashtable<String, String> set) {
		set.remove("path");
		set.remove("formPassword");
		return set;
	}
}
