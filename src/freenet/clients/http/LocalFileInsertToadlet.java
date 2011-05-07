/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.MalformedURLException;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;

public class LocalFileInsertToadlet extends LocalFileBrowserToadlet {
	
	public LocalFileInsertToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}
	
	public String path()
	{
		return "/insert-browse/";
	}
	
	protected String postTo()
	{
		return "/uploads/";
	}
	
	protected Hashtable<String, String> persistanceFields(Hashtable<String, String> set){
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		FreenetURI furi = null;
		String key = set.get("key");
		if(key != null) {
			try {
				furi = new FreenetURI(key);
			} catch (MalformedURLException e) {
				furi = null;
			}
		}
		
		String element = set.get("compress");
		if(element != null && Boolean.valueOf(element)) {
			fieldPairs.put("compress", element);
		}
		
		element = set.get("compatibilityMode"); 
		if(element != null) {
			fieldPairs.put("compatibilityMode", element);
		}
		
		if(furi != null)
		{
			fieldPairs.put("key", furi.toASCIIString());
		}
		
		element = set.get("overrideSplitfileKey");
		if(element != null) fieldPairs.put("overrideSplitfileKey", element);
		return fieldPairs;
	}
}
