package freenet.clients.http;

import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

public class LocalDirectoryConfigToadlet extends LocalFileBrowserToadlet {
	
	public LocalDirectoryConfigToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}

	@Override
	public String path() {
		return "/directory-config/";
	}

	@Override
	protected String postTo() {
		return "/config/node";
	}
	
	@Override
	protected void createInsertFileButton(HTMLNode fileRow, String filename, ToadletContext ctx, Hashtable<String, String> fieldPairs){
	}
	
	@Override
	protected void createInsertDirectoryButton(HTMLNode formNode, String path, ToadletContext ctx, Hashtable<String, String> fieldPairs) {
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "submit", "selected-dir", NodeL10n.getBase().getString("ConfigToadlet.selectDirectory")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "filename", path});
		createHiddenParamFields(formNode, fieldPairs);
	}

	@Override
	protected Hashtable<String, String> persistanceFields(Hashtable<String, String> set) {
		return set;
	}

}
