package freenet.clients.http;
import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;
import freenet.support.MultiValueTable;


public class LocalFileInsertToadlet extends LocalFileBrowserToadlet {
	private String overrideKey = null;
	
	public LocalFileInsertToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}
	
	public LocalFileInsertToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String overrideKey) {
		this(core, highLevelSimpleClient);
		this.overrideKey = overrideKey;
	}
	
	public String path()
	{
		return "/insert-browse/";
	}
	
	protected String postTo()
	{
		return "/uploads/";
	}
	
	protected void processParams(){
		// Clean out any previous values
		hiddenFieldName = new ArrayList<String>();
		hiddenFieldValue = new ArrayList<String>();
		
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
		
		// Build hidden field tables
		// FIXME: What are lengths for these?
		boolean compress = Boolean.valueOf(request.getPartAsStringFailsafe("compress", 4096));
		String compatibilityMode = request.getPartAsStringFailsafe("compatibilityMode", 4096);
		if(furi != null)
		{
			hiddenFieldName.add("key");
			hiddenFieldValue.add(furi.toASCIIString());
		}
		if (compress)
		{
			hiddenFieldName.add("compress");
			hiddenFieldValue.add(String.valueOf(compress));
		}
		hiddenFieldName.add("compatibilityMode");
		hiddenFieldValue.add(compatibilityMode);
		
		hiddenFieldName.add("overrideSplitfileKey");
		// FIXME: What is the length for this?
		hiddenFieldValue.add(request.getPartAsStringFailsafe("overrideSplitfileKey", 4096));
		return;
	}
}
