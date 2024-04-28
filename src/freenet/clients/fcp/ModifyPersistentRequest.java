/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

/**
 * FCP message: Modify a persistent request.
 * 
 * ModifyPersistentRequest
 * Identifier=request identifier
 * Verbosity=1023 // change verbosity
 * PriorityClass=1 // change priority class
 * ClientToken=new client token // change client token
 * MaxRetries=100 // change max retries
 * Global=true
 * EndMessage
 */
public class ModifyPersistentRequest extends FCPMessage {

	static final String NAME = "ModifyPersistentRequest";
	
	final String identifier;
	final boolean global;
	// negative means don't change
	final short priorityClass;
	final String clientToken;
	
	ModifyPersistentRequest(SimpleFieldSet fs) throws MessageInvalidException {
		this.global = fs.getBoolean("Global", false);
		this.identifier = fs.get("Identifier");
		this.clientToken = fs.get("ClientToken");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field: Identifier", null, global);
		String prio = fs.get("PriorityClass");
		if(prio != null) {
			try {
				priorityClass = Short.parseShort(prio);
				if(!RequestStarter.isValidPriorityClass(priorityClass))
					throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid priority class "+priorityClass+" - range is "+RequestStarter.PAUSED_PRIORITY_CLASS+" to "+RequestStarter.MAXIMUM_PRIORITY_CLASS, identifier, global);
			} catch (NumberFormatException e) {
				throw new MessageInvalidException(ProtocolErrorMessage.ERROR_PARSING_NUMBER, "Could not parse PriorityClass: "+e.getMessage(), identifier, global);
			}
		} else
			priorityClass = -1;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.put("Global", global);
		fs.put("PriorityClass", priorityClass);
		if(clientToken != null)
			fs.putSingle("ClientToken", clientToken);
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
		    try {
                node.getClientCore().getClientContext().jobRunner.queue(new PersistentJob() {
                    
                    @Override
                    public boolean run(ClientContext context) {
                        ClientRequest req = handler.getForeverRequest(global, handler, identifier);
                        if(req==null){
                            Logger.error(this, "Huh ? the request is null!");
                            ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
                            handler.send(msg);
                            return false;
                        } else {
                            req.modifyRequest(clientToken, priorityClass, handler.getServer());
                        }
                        return true;
                    }
                    
                }, NativeThread.NORM_PRIORITY);
            } catch (PersistenceDisabledException e) {
                ProtocolErrorMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.NO_SUCH_IDENTIFIER, false, null, identifier, global);
                handler.send(msg);
            }
		} else {
			req.modifyRequest(clientToken, priorityClass, node.getClientCore().getFCPServer());
		}
	}

}
