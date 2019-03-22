/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
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
		if(req == null) {
			if(node.clientCore.killedDatabase()) {
				// Ignore.
				return;
			}
			try {
                node.clientCore.clientContext.jobRunner.queue(new PersistentJob() {
                    
                    @Override
                    public boolean run(ClientContext context) {
                        ClientRequest req = handler.getForeverRequest(global, handler, identifier);
                        if(req == null) {
                            ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
                            handler.send(msg);
                        } else {
                            req.sendPendingMessages(handler.outputHandler, identifier, true, onlyData);
                        }
                        return false;
                    }
                    
                }, NativeThread.NORM_PRIORITY);
            } catch (PersistenceDisabledException e) {
                ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
                handler.send(msg);
            }
		} else {
			req.sendPendingMessages(handler.outputHandler, identifier, true, onlyData);
		}
	}

}
