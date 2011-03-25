package freenet.clients.http;

import java.util.ArrayList;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

public class LocalFileN2NMToadlet extends LocalFileBrowserToadlet {

	private String postTo;
	
	public String path() {
		return "/n2nm-browse/";
	}
	
	protected String postTo(){
		return "/send_n2ntm/";
	}
	
	public LocalFileN2NMToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}
	
	protected void createInsertDirectoryButton(HTMLNode fileRow, String path, ToadletContext ctx) {
		fileRow.addChild("td");
	}
	
	protected void processParams(){
		hiddenFieldName = new ArrayList<String>();
		hiddenFieldValue = new ArrayList<String>();
		
		hiddenFieldName.add("message");
		hiddenFieldValue.add(request.getPartAsStringFailsafe("message", 5 * 1024));
		
		for(String partName : request.getParts())
		{
			if(partName.startsWith("node_"))
			{
				hiddenFieldName.add(partName);
				hiddenFieldValue.add("1");
			}
		}
	}
}
