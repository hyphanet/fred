package freenet.node.fcp;

import freenet.client.events.SplitfileProgressEvent;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SimpleProgressMessage extends FCPMessage {

	private final String ident;
	private final SplitfileProgressEvent event;
	
	public SimpleProgressMessage(String identifier, SplitfileProgressEvent event) {
		this.ident = identifier;
		this.event = event;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(false);
		fs.put("Total", Integer.toString(event.totalBlocks));
		fs.put("Required", Integer.toString(event.minSuccessfulBlocks));
		fs.put("Failed", Integer.toString(event.failedBlocks));
		fs.put("FatallyFailed", Integer.toString(event.fatallyFailedBlocks));
		fs.put("Succeeded",Integer.toString(event.fetchedBlocks));
		fs.put("FinalizedTotal", Boolean.toString(event.finalizedTotal));
		fs.put("Identifier", ident);
		return fs;
	}

	public String getName() {
		return "SimpleProgress";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SimpleProgress goes from server to client not the other way around", ident);
	}

	public double getFraction() {
		return (double) event.fetchedBlocks / (double) event.totalBlocks;
	}

}
