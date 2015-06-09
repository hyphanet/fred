/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.util.Date;

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
	
	protected SimpleProgressMessage() {
	    // For serialization.
	    ident = null;
	    global = false;
	    event = null;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("Total", event.totalBlocks);
		fs.put("Required", event.minSuccessfulBlocks);
		fs.put("Failed", event.failedBlocks);
		fs.put("FatallyFailed", event.fatallyFailedBlocks);
		/* FIXME: This field has been disabled since it will always be 0 (= "never") even if
		 * parts of the file transfer failed due to temporary reasons such as "data not found" /
		 * "route not found" / etc. This is due to shortcomings in the underlying event framework.
		 * Please re-enable it once the underlying issue is fixed:
		 * https://bugs.freenetproject.org/view.php?id=6526 */
		// fs.put("LastFailure", event.latestFailure != null ? event.latestFailure.getTime() : 0);
		fs.put("Succeeded",event.succeedBlocks);
		fs.put("LastProgress", event.latestSuccess != null ? event.latestSuccess.getTime() : 0);
		fs.put("FinalizedTotal", event.finalizedTotal);
		if(event.minSuccessFetchBlocks != 0)
			fs.put("MinSuccessFetchBlocks", event.minSuccessFetchBlocks);
		fs.putSingle("Identifier", ident);
		fs.put("Global", global);
		return fs;
	}

	@Override
	public String getName() {
		return "SimpleProgress";
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "SimpleProgress goes from server to client not the other way around", ident, global);
	}

	public double getFraction() {
		return (double) event.succeedBlocks / (double) event.totalBlocks;
	}
	
	public double getMinBlocks() {
		return event.minSuccessfulBlocks;
	}
	
	public double getTotalBlocks(){
		return event.totalBlocks;
	}
	
	public double getFetchedBlocks(){
		return event.succeedBlocks;
	}
	
	public Date getLatestSuccess() {
		// clone() because Date is mutable
		return event.latestSuccess != null ? (Date)event.latestSuccess.clone() : null;
	}
	
	public double getFailedBlocks(){
		return event.failedBlocks;
	}
	
	public double getFatalyFailedBlocks(){
		return event.fatallyFailedBlocks;
	}
	
	public Date getLatestFailure() {
		// clone() because Date is mutable
		return event.latestFailure != null ? (Date)event.latestFailure.clone() : null;
	}

	public boolean isTotalFinalized() {
		return event.finalizedTotal;
	}

	SplitfileProgressEvent getEvent() {
		return event;
	}

}
