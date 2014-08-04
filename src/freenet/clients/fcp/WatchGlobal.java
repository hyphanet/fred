/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class WatchGlobal extends FCPMessage {

	final boolean enabled;
	final int verbosityMask;
	static final String NAME = "WatchGlobal";

	public WatchGlobal(SimpleFieldSet fs) throws MessageInvalidException {
		enabled = fs.getBoolean("Enabled", true);
		String s = fs.get("VerbosityMask");
		if(s != null)
			try {
				verbosityMask = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, e.toString(), null, false);
			}
		else
			verbosityMask = Integer.MAX_VALUE;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Enabled", enabled);
		fs.put("VerbosityMask", verbosityMask);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.getRebootClient().setWatchGlobal(enabled, verbosityMask, node.clientCore.getFCPServer(), null)) {
			FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence disabled", null, true);
			handler.outputHandler.queue(err);
		}
		FCPClient client = handler.getForeverClient(null);
		if(client != null)
		    client.setWatchGlobal(enabled, verbosityMask, handler.server, null);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
