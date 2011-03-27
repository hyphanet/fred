/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.util.Hashtable;
import java.util.Set;

import freenet.client.HighLevelSimpleClient;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;

public class LocalFileN2NMToadlet extends LocalFileBrowserToadlet {
	
	public String path() {
		return "/n2nm-browse/";
	}
	
	protected String postTo(){
		return "/send_n2ntm/";
	}
	
	public LocalFileN2NMToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}
	
	protected void createInsertDirectoryButton(HTMLNode fileRow, String path, ToadletContext ctx, Hashtable<String, String> fieldPairs) {
		fileRow.addChild("td");
	}
	
	protected Hashtable<String, String> persistanceFields(Hashtable<String, String> set){
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		String message = set.get("message");
		if(message != null) fieldPairs.put("message", message);
		Set<String> keys = set.keySet();
		for(String key : keys)
		{
			if(key.startsWith("node_"))
			{
				fieldPairs.put(key, "1");
			}
		}
		return fieldPairs;
	}
}
