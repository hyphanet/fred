/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashMap;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.support.Logger;

/**
 * <P>plain/dumb manifest putter: every file item is a redirect (no containers at all)
 * 
 * <P>default doc:<BR>
 * defaultName is just the name, without any '/'!<BR>
 * each item &lt;defaultName&gt; is the default doc in the corresponding dir.
 */

public class PlainManifestPutter extends BaseManifestPutter {

	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(PlainManifestPutter.class);
	}

	public PlainManifestPutter(ClientPutCallback clientCallback, HashMap<String, Object> manifestElements, short prioClass, FreenetURI target, String defaultName, InsertContext ctx, boolean getCHKOnly,
			RequestClient clientContext, boolean earlyEncode, boolean persistent, byte [] forceCryptoKey, ObjectContainer container, ClientContext context) {
		super(clientCallback, manifestElements, prioClass, target, defaultName, ctx, getCHKOnly, clientContext, earlyEncode, ClientPutter.randomiseSplitfileKeys(target, ctx, persistent, container), forceCryptoKey, context);
	}

	@Override
	protected void makePutHandlers(HashMap<String,Object> manifestElements, String defaultName) {
		if(logDEBUG) Logger.debug(this, "Root map : "+manifestElements.size()+" elements");
		makePutHandlers(getRootBuilder(), manifestElements, defaultName);
	}
	
	@SuppressWarnings("unchecked")
	private void makePutHandlers(FreeFormBuilder builder, HashMap<String, Object> manifestElements, Object defaultName) {
		for(String name: manifestElements.keySet()) {
			Object o = manifestElements.get(name);
			if(o instanceof HashMap) {
				HashMap<String,Object> subMap = (HashMap<String,Object>)o;
				builder.pushCurrentDir();
				builder.makeSubDirCD(name);
				makePutHandlers(builder, subMap, defaultName);
				builder.popCurrentDir();
				if(logDEBUG) Logger.debug(this, "Sub map for "+name+" : "+subMap.size()+" elements");
			} else {
				ManifestElement element = (ManifestElement) o;
				builder.addElement(name, element, name.equals(defaultName));
			}
		}
	}
}

