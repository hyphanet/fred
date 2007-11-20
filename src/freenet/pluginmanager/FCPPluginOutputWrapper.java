/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPConnectionHandler;
import freenet.node.fcp.FCPPluginReply;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;

/**
 * @author saces
 *
 */
public class FCPPluginOutputWrapper {
	
	private final String identifier;
	private final String plugname;
	private final FCPConnectionHandler handler;
	

	public FCPPluginOutputWrapper(FCPConnectionHandler handler2, String pluginname, String identifier2) {
		identifier = identifier2;
		plugname = pluginname;
		handler = handler2;
	}
	
	public void send(SimpleFieldSet params) {
		FCPPluginReply reply = new FCPPluginReply(plugname, identifier, params, null);
		handler.outputHandler.queue(reply);
	}
	
	public void send(SimpleFieldSet params, byte[] data) {
		FCPPluginReply reply = new FCPPluginReply(plugname, identifier, params, new ArrayBucket(data));
		handler.outputHandler.queue(reply);
	}
	
	public void send(SimpleFieldSet params, Bucket bucket) {
		FCPPluginReply reply = new FCPPluginReply(plugname, identifier, params, bucket);
		handler.outputHandler.queue(reply);
	}

}
