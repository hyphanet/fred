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
import freenet.support.Logger;
import freenet.support.api.BucketFactory;
import freenet.support.compress.RealCompressor;
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
	public transient final RealCompressor rc;

	public ClientContext(NodeClientCore core, FECQueue fecQueue, Executor mainExecutor,
			BackgroundBlockEncoder blockEncoder, ArchiveManager archiveManager,
			BucketFactory ptbf, BucketFactory tbf, HealingQueue hq,
			USKManager uskManager, RandomSource strongRandom, 
			Random fastWeakRandom, Ticker ticker, 
			FilenameGenerator fg, FilenameGenerator persistentFG, RealCompressor rc) {
		this.bootID = core.node.bootID;
		this.fecQueue = fecQueue;
		jobRunner = core;
		this.mainExecutor = mainExecutor;
		this.nodeDBHandle = core.node.nodeDBHandle;
		this.backgroundBlockEncoder = blockEncoder;
		this.random = strongRandom;
		this.archiveManager = archiveManager;
		this.persistentBucketFactory = ptbf;
		if(persistentBucketFactory == null) throw new NullPointerException();
		this.tempBucketFactory = tbf;
		if(tempBucketFactory == null) throw new NullPointerException();
		this.healingQueue = hq;
		this.uskManager = uskManager;
		this.fastWeakRandom = fastWeakRandom;
		this.ticker = ticker;
		this.fg = fg;
		this.persistentFG = persistentFG;
		this.rc = rc;
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
	
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientContext in database", new Exception("error"));
		return false;
	}
	
}
