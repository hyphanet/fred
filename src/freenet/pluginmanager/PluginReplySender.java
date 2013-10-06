/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPCallFailedException;
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
	
	public String getPluginName() {
		return pluginname;
	}
	
	public String getIdentifier() {
		return identifier;
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

	/**
	 * In opposite to send, this waits for the client to process the reply.
	 * This allows it to throw {@link Throwable} if sending the reply to the client failed.
	 * In case of {@link PluginReplySenderDirect}, the {@link Throwable} will even be the {@link Throwable} which the client's
	 * {@link FredPluginTalker#onReply(String, String, SimpleFieldSet, Bucket) threw.
	 * @throws FCPCallFailedException If the processing of the message failed at the client.
	 */
    public abstract void sendSynchronous(SimpleFieldSet params, Bucket bucket) throws FCPCallFailedException;
}