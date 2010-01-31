/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;

public abstract class PluginReplySender {
	
	final String pluginname;
	final String identifier;	
	
	public PluginReplySender(String pluginname2, String identifier2) {
		pluginname = pluginname2;
		identifier = identifier2;
	}

	public void send(SimpleFieldSet params) throws PluginNotFoundException {
		send(params, (Bucket)null);
	}
	
	public void send(SimpleFieldSet params, byte[] data) throws PluginNotFoundException {
		if (data == null)
			send(params, (Bucket)null);
		else
			send(params, new ArrayBucket(data));
	}
	
	public abstract void send(SimpleFieldSet params, Bucket bucket) throws PluginNotFoundException;

}
