/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class GetRequestStatusMessage extends FCPMessage {

	final String identifier;
	final boolean global;
	final boolean onlyData;
	final static String NAME = "GetRequestStatus";
	
	public GetRequestStatusMessage(SimpleFieldSet fs) {
		this.identifier = fs.get("Identifier");
		this.global = fs.getBoolean("Global", false);
		this.onlyData = fs.getBoolean("OnlyData", false);
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		return fs;
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		ClientRequest req = handler.getRebootRequest(global, handler, identifier);
		if(req == null && !node.clientCore.killedDatabase()) {
			try {
				node.clientCore.clientContext.jobRunner.queue(new DBJob() {

					@Override
					public boolean run(ObjectContainer container, ClientContext context) {
						ClientRequest req = handler.getForeverRequest(global, handler, identifier, container);
						container.activate(req, 1);
						if(req == null) {
							ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
							handler.outputHandler.queue(msg);
						} else {
							req.sendPendingMessages(handler.outputHandler, true, true, onlyData, container);
						}
						container.deactivate(req, 1);
						return false;
					}
					
				}, NativeThread.NORM_PRIORITY, false);
			} catch (DatabaseDisabledException e) {
				ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
				handler.outputHandler.queue(msg);
			}
		} else {
			req.sendPendingMessages(handler.outputHandler, true, true, onlyData, null);
		}
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
