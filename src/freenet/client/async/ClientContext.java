/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FECQueue;
import freenet.node.NodeClientCore;
import freenet.support.Executor;

/**
 * Object passed in to client-layer operations, containing references to essential but transient objects
 * such as the schedulers and the FEC queue.
 * @author toad
 */
public class ClientContext {
	
	public final FECQueue fecQueue;
	public final ClientRequestScheduler sskFetchScheduler;
	public final ClientRequestScheduler chkFetchScheduler;
	public final ClientRequestScheduler sskInsertScheduler;
	public final ClientRequestScheduler chkInsertScheduler;
	public final DBJobRunner jobRunner;
	public final Executor mainExecutor;
	public final long nodeDBHandle;

	public ClientContext(NodeClientCore core) {
		this.fecQueue = core.fecQueue;
		this.sskFetchScheduler = core.requestStarters.sskFetchScheduler;
		this.chkFetchScheduler = core.requestStarters.chkFetchScheduler;
		this.sskInsertScheduler = core.requestStarters.sskPutScheduler;
		this.chkInsertScheduler = core.requestStarters.chkPutScheduler;
		jobRunner = core;
		this.mainExecutor = core.getExecutor();
		this.nodeDBHandle = core.node.nodeDBHandle;
	}
	
}
