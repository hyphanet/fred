package freenet.clients.http;

import java.io.File;
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
	protected String startingDir() {
		//Start out in user home directory.
		return System.getProperty("user.home");
	}

	@Override
	protected boolean allowedDir(File path) {
		//When configuring, can select any directory.
		return true;
	}
	
	@Override
	protected void createSelectDirectoryButton (HTMLNode formNode, String path, HTMLNode persist) {
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
		        new String[] { "submit", selectDir,
		                NodeL10n.getBase().getString("ConfigToadlet.selectDirectory")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
		        new String[] { "hidden", filenameField(), path});
		formNode.addChild(persist);
	}

	@Override
	protected  Hashtable<String, String> persistenceFields (Hashtable<String, String> set) {
		set.remove("path");
		set.remove("formPassword");
		return set;
	}
}
