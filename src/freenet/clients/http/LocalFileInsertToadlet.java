/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.node.NodeClientCore;

public class LocalFileInsertToadlet extends LocalFileBrowserToadlet {

	public static final String PATH = "/insert-browse/";
	public static final String POST_TO = "/uploads/";

	public LocalFileInsertToadlet (NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
		super(core, highLevelSimpleClient);
	}

	@Override
	public String path() {
		return PATH;
	}

	@Override
	protected String postTo() {
		return POST_TO;
	}

	@Override
	protected boolean allowedDir(File path) {
		return core.allowUploadFrom(path);
	}

	@Override
	protected String startingDir() {
		return defaultUploadDir();
	}

    @Override
	protected Hashtable<String, String> persistenceFields (Hashtable<String, String> set) {
		Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
		FreenetURI furi = null;
		String key = set.get("key");
		if (key != null) {
			try {
				furi = new FreenetURI(key);
			} catch (MalformedURLException e) {
				furi = null;
			}
		}

		String element = set.get("compress");
		if (element != null && Boolean.valueOf(element)) {
			fieldPairs.put("compress", element);
		}

		element = set.get("compatibilityMode"); 
		if (element != null) {
			fieldPairs.put("compatibilityMode", element);
		}

		if (furi != null) {
			fieldPairs.put("key", furi.toASCIIString());
		}

		element = set.get("overrideSplitfileKey");
		if (element != null) fieldPairs.put("overrideSplitfileKey", element);
		return fieldPairs;
	}
}
