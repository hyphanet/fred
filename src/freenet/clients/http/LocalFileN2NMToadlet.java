package freenet.clients.http;

import java.util.ArrayList;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

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
	
	protected ArrayList<ArrayList<String>> processParams(HTTPRequest request){
		ArrayList<ArrayList<String>> fieldPairs = new ArrayList<ArrayList<String>>();
		fieldPairs.add(makePair("message", request.getPartAsStringFailsafe("message", 5 * 1024)));
		
		for(String partName : request.getParts())
		{
			if(partName.startsWith("node_"))
			{
				fieldPairs.add(makePair(partName, "1"));
			}
		}
		return fieldPairs;
	}
}
