/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.client.events.SplitfileProgressEvent;

public class SimpleProgressMessage extends FCPMessage {

	private final String ident;
	private final boolean global;
	private final SplitfileProgressEvent event;
	
	public SimpleProgressMessage(String identifier, boolean global, SplitfileProgressEvent event) {
		this.ident = identifier;
		this.event = event;
		this.global = global;
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
	
	public double getFailedBlocks(){
		return event.failedBlocks;
	}
	
	public double getFatalyFailedBlocks(){
		return event.fatallyFailedBlocks;
	}

	public boolean isTotalFinalized() {
		return event.finalizedTotal;
	}

	SplitfileProgressEvent getEvent() {
		return event;
	}

}
