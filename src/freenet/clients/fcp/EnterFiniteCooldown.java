package freenet.clients.fcp;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

/** Status message sent when the whole of a request is waiting for a cooldown.
 * Not when it's all running - that would be a different event.
 * @author toad
 */
public class EnterFiniteCooldown extends FCPMessage {
	
	final String identifier;
	final boolean global;
	final long wakeupTime;

	EnterFiniteCooldown(String identifier, boolean global, long wakeupTime) {
		this.identifier = identifier;
		this.global = global;
		this.wakeupTime = wakeupTime;
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.putOverwrite("Identifier", identifier);
		fs.put("Global", global);
		fs.put("WakeupTime", wakeupTime);
		return fs;
	}

	@Override
	public String getName() {
		return "EnterFiniteCooldown";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		// Not supported
	}

}
