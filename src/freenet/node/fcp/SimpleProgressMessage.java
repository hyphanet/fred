/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.events.SplitfileProgressEvent;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class SimpleProgressMessage extends FCPMessage {

	private final String ident;
	private final boolean global;
	private final SplitfileProgressEvent event;
	
	public SimpleProgressMessage(String identifier, boolean global, SplitfileProgressEvent event) {
		this.ident = identifier;
		this.event = event;
		this.global = global;
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Total", event.totalBlocks);
		fs.put("Required", event.minSuccessfulBlocks);
		fs.put("Failed", event.failedBlocks);
		fs.put("FatallyFailed", event.fatallyFailedBlocks);
		fs.put("Succeeded",event.fetchedBlocks);
		fs.put("FinalizedTotal", event.finalizedTotal);
		fs.putSingle("Identifier", ident);
		if(global) fs.putSingle("Global", "true");
		return fs;
	}

	public String getName() {
		return "SimpleProgress";
	}

	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SimpleProgress goes from server to client not the other way around", ident, global);
	}

	public double getFraction() {
		return (double) event.fetchedBlocks / (double) event.totalBlocks;
	}
	
	public double getMinBlocks() {
		return event.minSuccessfulBlocks;
	}
	
	public double getTotalBlocks(){
		return event.totalBlocks;
	}
	
	public double getFetchedBlocks(){
		return event.fetchedBlocks;
	}
	
	public double getFailedBlocks(){
		return event.failedBlocks;
	}
	
	public double getFatalyFailedBlocks(){
		return event.fatallyFailedBlocks;
	}

	public boolean isTotalFinalized() {
		return event.finalizedTotal;
	}

}
