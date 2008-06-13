/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveManager;
import freenet.client.FECQueue;
import freenet.client.FetchException;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.node.NodeClientCore;
import freenet.support.Executor;
import freenet.support.io.NativeThread;

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
	public final BackgroundBlockEncoder backgroundBlockEncoder;
	public final RandomSource random;
	public final ArchiveManager archiveManager;

	public ClientContext(NodeClientCore core) {
		this.fecQueue = core.fecQueue;
		this.sskFetchScheduler = core.requestStarters.sskFetchScheduler;
		this.chkFetchScheduler = core.requestStarters.chkFetchScheduler;
		this.sskInsertScheduler = core.requestStarters.sskPutScheduler;
		this.chkInsertScheduler = core.requestStarters.chkPutScheduler;
		jobRunner = core;
		this.mainExecutor = core.getExecutor();
		this.nodeDBHandle = core.node.nodeDBHandle;
		this.backgroundBlockEncoder = core.backgroundBlockEncoder;
		this.random = core.random;
		archiveManager = core.archiveManager;
	}

	public void start(final ClientPutter inserter, final boolean earlyEncode) throws InsertException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					try {
						inserter.start(earlyEncode, false, container, context);
					} catch (InsertException e) {
						inserter.client.onFailure(e, inserter);
					}
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(earlyEncode, false, null, this);
		}
	}

	public void start(final ClientGetter getter) throws FetchException {
		if(getter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					try {
						getter.start(container, context);
					} catch (FetchException e) {
						getter.clientCallback.onFailure(e, getter);
					}
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			getter.start(null, this);
		}
	}

	public void start(final SimpleManifestPutter inserter) throws InsertException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					try {
						inserter.start(container, context);
					} catch (InsertException e) {
						inserter.cb.onFailure(e, inserter);
					}
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(null, this);
		}
	}
	
}
