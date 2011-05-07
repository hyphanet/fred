package freenet.clients.http;

import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

public class LocalDirectoryConfigToadlet extends LocalFileBrowserToadlet {

	private final String postTo;

	public LocalDirectoryConfigToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String postTo) {
		super(core, highLevelSimpleClient);
		this.postTo = postTo;
	}

	@Override
	public String path() {
		return "/directory-browser"+postTo;
	}

	@Override
	protected String postTo() {
		return postTo;
	}
	
	@Override
	protected void createInsertFileButton(HTMLNode fileRow, String filename){
	}
	
	@Override
	protected void createInsertDirectoryButton(HTMLNode formNode, String path) {
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "submit", "selected-dir", NodeL10n.getBase().getString("ConfigToadlet.selectDirectory")});
		formNode.addChild("input", new String[] { "type", "name", "value" }, 
				new String[] { "hidden", "filename", path});
	}

    @Override
	protected Hashtable<String, String> persistenceFields(Hashtable<String, String> set) {
		//Path is set by each button and should not be persisted separately.
		set.remove("path");
		return set;
	}

}
