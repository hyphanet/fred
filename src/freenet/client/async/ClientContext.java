/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Random;

import com.db4o.ObjectContainer;

import freenet.client.ArchiveManager;
import freenet.client.FECQueue;
import freenet.client.FetchException;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.node.NodeClientCore;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarterGroup;
import freenet.node.Ticker;
import freenet.support.Executor;
import freenet.support.api.BucketFactory;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.NativeThread;

/**
 * Object passed in to client-layer operations, containing references to essential but transient objects
 * such as the schedulers and the FEC queue.
 * @author toad
 */
public class ClientContext {
	
	public transient final FECQueue fecQueue;
	private transient ClientRequestScheduler sskFetchScheduler;
	private transient ClientRequestScheduler chkFetchScheduler;
	private transient ClientRequestScheduler sskInsertScheduler;
	private transient ClientRequestScheduler chkInsertScheduler;
	public transient final DBJobRunner jobRunner;
	public transient final Executor mainExecutor;
	public transient final long nodeDBHandle;
	public transient final BackgroundBlockEncoder backgroundBlockEncoder;
	public transient final RandomSource random;
	public transient final ArchiveManager archiveManager;
	public transient final BucketFactory persistentBucketFactory;
	public transient final BucketFactory tempBucketFactory;
	public transient final HealingQueue healingQueue;
	public transient final USKManager uskManager;
	public transient final Random fastWeakRandom;
	public transient final long bootID;
	public transient final Ticker ticker;
	public transient final FilenameGenerator fg;
	public transient final FilenameGenerator persistentFG;

	public ClientContext(NodeClientCore core) {
		this.bootID = core.node.bootID;
		this.fecQueue = core.fecQueue;
		jobRunner = core;
		this.mainExecutor = core.getExecutor();
		this.nodeDBHandle = core.node.nodeDBHandle;
		this.backgroundBlockEncoder = core.backgroundBlockEncoder;
		this.random = core.random;
		archiveManager = core.archiveManager;
		this.persistentBucketFactory = core.persistentTempBucketFactory;
		this.tempBucketFactory = core.tempBucketFactory;
		this.healingQueue = core.getHealingQueue();
		this.uskManager = core.uskManager;
		fastWeakRandom = core.node.fastWeakRandom;
		this.ticker = core.getTicker();
		fg = core.tempFilenameGenerator;
		persistentFG = core.persistentFilenameGenerator;
	}
	
	public void init(RequestStarterGroup starters) {
		this.sskFetchScheduler = starters.sskFetchScheduler;
		this.chkFetchScheduler = starters.chkFetchScheduler;
		this.sskInsertScheduler = starters.sskPutScheduler;
		this.chkInsertScheduler = starters.chkPutScheduler;
	}

	public ClientRequestScheduler getSskFetchScheduler() {
		return sskFetchScheduler;
	}
	
	public ClientRequestScheduler getChkFetchScheduler() {
		return chkFetchScheduler;
	}
	
	public ClientRequestScheduler getSskInsertScheduler() {
		return sskInsertScheduler;
	}
	
	public ClientRequestScheduler getChkInsertScheduler() {
		return chkInsertScheduler;
	}
	
	public void start(final ClientPutter inserter, final boolean earlyEncode) throws InsertException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					container.activate(inserter, 1);
					try {
						inserter.start(earlyEncode, false, container, context);
					} catch (InsertException e) {
						inserter.client.onFailure(e, inserter, container);
					}
					container.deactivate(inserter, 1);
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
					container.activate(getter, 1);
					try {
						getter.start(container, context);
					} catch (FetchException e) {
						getter.clientCallback.onFailure(e, getter, container);
					}
					container.deactivate(getter, 1);
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
					container.activate(inserter, 1);
					try {
						inserter.start(container, context);
					} catch (InsertException e) {
						inserter.cb.onFailure(e, inserter, container);
					}
					container.deactivate(inserter, 1);
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(null, this);
		}
	}

	public BucketFactory getBucketFactory(boolean persistent) {
		if(persistent)
			return persistentBucketFactory;
		else
			return tempBucketFactory;
	}

	public RequestScheduler getFetchScheduler(boolean ssk) {
		if(ssk) return sskFetchScheduler;
		return chkFetchScheduler;
	}
	
}
