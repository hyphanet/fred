package freenet.clients.http;
import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.MultiValueTable;
import freenet.support.api.HTTPRequest;


public class LocalFileInsertToadlet extends LocalFileBrowserToadlet {
	private String overrideKey = null;
	
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
	
	public void setOverrideKey(String overrideKey)
	{
		this.overrideKey = overrideKey;
	}
	
	protected ArrayList<ArrayList<String>> processParams(HTTPRequest request){
		ArrayList<ArrayList<String>> fieldPairs = new ArrayList<ArrayList<String>>();
		FreenetURI furi = null;
		String key;
		if(overrideKey == null)
		{
			key = request.getPartAsStringFailsafe("key", QueueToadlet.MAX_KEY_LENGTH);
		}
		else
		{
			key = overrideKey;
			overrideKey = null;
		}
		
		if(key != null) {
			try {
				furi = new FreenetURI(key);
			} catch (MalformedURLException e) {
				furi = null;
			}
		}
		
		// FIXME: What are lengths for these?
		boolean compress = Boolean.valueOf(request.getPartAsStringFailsafe("compress", 4096));
		String compatibilityMode = request.getPartAsStringFailsafe("compatibilityMode", 4096);
		if(furi != null)
		{
			fieldPairs.add(makePair("key",furi.toASCIIString()));
		}
		if (compress)
		{
			fieldPairs.add(makePair("compress",String.valueOf(compress)));
		}
		fieldPairs.add(makePair("compatibilityMode",compatibilityMode));
		// FIXME: What is the length of a splitfile key?
		fieldPairs.add(makePair("overrideSplitfileKey", request.getPartAsStringFailsafe("overrideSplitfileKey", 4096)));
		return fieldPairs;
	}
}
