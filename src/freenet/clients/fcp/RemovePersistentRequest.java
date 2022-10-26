/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

/**
 * Client telling node to remove a (completed or not) persistent request.
 */
public class RemovePersistentRequest extends FCPMessage {

	final static String NAME = "RemoveRequest";
	final static String ALT_NAME = "RemovePersistentRequest";
	
	final String identifier;
	final boolean global;
	
	public RemovePersistentRequest(SimpleFieldSet fs) throws MessageInvalidException {
		this.global = fs.getBoolean("Global", false);
		this.identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Must have Identifier", null, global);
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
		ClientRequest req = handler.removePersistentRebootRequest(global, identifier);
		if(req == null && !global) {
			req = handler.removeRequestByIdentifier(identifier, true);
		}
		if(req == null) {
		    try {
                handler.server.core.clientContext.jobRunner.queue(new PersistentJob() {
                    
                    @Override
                    public boolean run(ClientContext context) {
                        try {
                            ClientRequest req = handler.removePersistentForeverRequest(global, identifier);
                            if(req == null) {
                                Logger.error(this, "Huh ? the request is null!");
                                return false;
                            }
                            return true;
                        } catch (MessageInvalidException e) {
                            FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
                            handler.send(err);
                            return false;
                        }
                    }
                    
                }, NativeThread.HIGH_PRIORITY);
            } catch (PersistenceDisabledException e) {
                FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.PERSISTENCE_DISABLED, false, "Persistence disabled and non-persistent request not found", identifier, global);
                handler.send(err);
            }
		}
	}

}
