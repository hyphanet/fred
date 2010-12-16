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
import freenet.node.RequestScheduler;
import freenet.node.RequestStarterGroup;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.api.BucketFactory;
import freenet.support.compress.RealCompressor;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.NativeThread;
import freenet.support.io.PersistentFileTracker;
import freenet.support.io.PersistentTempBucketFactory;

/**
 * Object passed in to client-layer operations, containing references to essential but mostly transient 
 * objects such as the schedulers and the FEC queue.
 * @author toad
 */
public class ClientContext {
	
	public transient FECQueue fecQueue;
	private transient ClientRequestScheduler sskFetchSchedulerBulk;
	private transient ClientRequestScheduler chkFetchSchedulerBulk;
	private transient ClientRequestScheduler sskInsertSchedulerBulk;
	private transient ClientRequestScheduler chkInsertSchedulerBulk;
	private transient ClientRequestScheduler sskFetchSchedulerRT;
	private transient ClientRequestScheduler chkFetchSchedulerRT;
	private transient ClientRequestScheduler sskInsertSchedulerRT;
	private transient ClientRequestScheduler chkInsertSchedulerRT;
	private transient UserAlertManager alerts;
	public transient final DBJobRunner jobRunner;
	public transient final Executor mainExecutor;
	public transient final long nodeDBHandle;
	public transient final BackgroundBlockEncoder backgroundBlockEncoder;
	public transient final RandomSource random;
	public transient final ArchiveManager archiveManager;
	public transient PersistentTempBucketFactory persistentBucketFactory;
	public transient PersistentFileTracker persistentFileTracker;
	public transient final BucketFactory tempBucketFactory;
	public transient final HealingQueue healingQueue;
	public transient final USKManager uskManager;
	public transient final Random fastWeakRandom;
	public transient final long bootID;
	public transient final Ticker ticker;
	public transient final FilenameGenerator fg;
	public transient FilenameGenerator persistentFG;
	public transient final RealCompressor rc;
	public transient final DatastoreChecker checker;
	public transient final CooldownTracker cooldownTracker;
	public transient DownloadCache downloadCache;

	public ClientContext(long bootID, long nodeDBHandle, DBJobRunner jobRunner, FECQueue fecQueue, Executor mainExecutor,
			BackgroundBlockEncoder blockEncoder, ArchiveManager archiveManager,
			PersistentTempBucketFactory ptbf, BucketFactory tbf, PersistentFileTracker tracker, HealingQueue hq,
			USKManager uskManager, RandomSource strongRandom, 
			Random fastWeakRandom, Ticker ticker, 
			FilenameGenerator fg, FilenameGenerator persistentFG, RealCompressor rc, DatastoreChecker checker) {
		this.bootID = bootID;
		this.fecQueue = fecQueue;
		this.jobRunner = jobRunner;
		this.mainExecutor = mainExecutor;
		this.nodeDBHandle = nodeDBHandle;
		this.backgroundBlockEncoder = blockEncoder;
		this.random = strongRandom;
		this.archiveManager = archiveManager;
		this.persistentBucketFactory = ptbf;
		this.tempBucketFactory = tbf;
		this.healingQueue = hq;
		this.uskManager = uskManager;
		this.fastWeakRandom = fastWeakRandom;
		this.ticker = ticker;
		this.fg = fg;
		this.persistentFG = persistentFG;
		this.rc = rc;
		this.checker = checker;
		this.cooldownTracker = new CooldownTracker();
	}
	
	public void init(RequestStarterGroup starters, UserAlertManager alerts) {
		this.sskFetchSchedulerBulk = starters.sskFetchSchedulerBulk;
		this.chkFetchSchedulerBulk = starters.chkFetchSchedulerBulk;
		this.sskInsertSchedulerBulk = starters.sskPutSchedulerBulk;
		this.chkInsertSchedulerBulk = starters.chkPutSchedulerBulk;
		this.sskFetchSchedulerRT = starters.sskFetchSchedulerRT;
		this.chkFetchSchedulerRT = starters.chkFetchSchedulerRT;
		this.sskInsertSchedulerRT = starters.sskPutSchedulerRT;
		this.chkInsertSchedulerRT = starters.chkPutSchedulerRT;
		this.alerts = alerts;
		this.cooldownTracker.startMaintenance(ticker);
	}

	public ClientRequestScheduler getSskFetchScheduler(boolean realTime) {
		return realTime ? sskFetchSchedulerRT : sskFetchSchedulerBulk;
	}
	
	public ClientRequestScheduler getChkFetchScheduler(boolean realTime) {
		return realTime ? chkFetchSchedulerRT : chkFetchSchedulerBulk;
	}
	
	public ClientRequestScheduler getSskInsertScheduler(boolean realTime) {
		return realTime ? sskInsertSchedulerRT : sskInsertSchedulerBulk;
	}
	
	public ClientRequestScheduler getChkInsertScheduler(boolean realTime) {
		return realTime ? chkInsertSchedulerRT : chkInsertSchedulerBulk;
	}
	
	/** 
	 * Start an insert. Queue a database job if it is a persistent insert, otherwise start it right now.
	 * @param inserter The insert to start.
	 * @param earlyEncode Whether to try to encode the data and insert the upper layers as soon as possible.
	 * Normally we wait for each layer to complete before inserting the next one because an attacker may be
	 * able to identify lower blocks once the top block has been inserted (e.g. if it's a known SSK).
	 * @throws InsertException If the insert is transient and it fails to start.
	 * @throws DatabaseDisabledException If the insert is persistent and the database is disabled (e.g. 
	 * because it is encrypted and the user hasn't entered the password yet).
	 */
	public void start(final ClientPutter inserter, final boolean earlyEncode) throws InsertException, DatabaseDisabledException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public boolean run(ObjectContainer container, ClientContext context) {
					container.activate(inserter, 1);
					try {
						inserter.start(earlyEncode, false, container, context);
					} catch (InsertException e) {
						inserter.client.onFailure(e, inserter, container);
					}
					container.deactivate(inserter, 1);
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(earlyEncode, false, null, this);
		}
	}

	/**
	 * Start a request. Schedule a job on the database thread if it is persistent, otherwise start it 
	 * immediately.
	 * @param getter The request to start.
	 * @throws FetchException If the request is transient and failed to start.
	 * @throws DatabaseDisabledException If the request is persistent and the database is disabled.
	 */
	public void start(final ClientGetter getter) throws FetchException, DatabaseDisabledException {
		if(getter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public boolean run(ObjectContainer container, ClientContext context) {
					container.activate(getter, 1);
					try {
						getter.start(container, context);
					} catch (FetchException e) {
						getter.clientCallback.onFailure(e, getter, container);
					}
					container.deactivate(getter, 1);
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			getter.start(null, this);
		}
	}

	/**
	 * Start a site insert. Schedule a job on the database thread if it is persistent, otherwise start it 
	 * immediately.
	 * @param inserter The request to start.
	 * @throws InsertException If the insert is transient and failed to start.
	 * @throws DatabaseDisabledException If the insert is persistent and the database is disabled.
	 */
	public void start(final SimpleManifestPutter inserter) throws InsertException, DatabaseDisabledException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public boolean run(ObjectContainer container, ClientContext context) {
					container.activate(inserter, 1);
					try {
						inserter.start(container, context);
					} catch (InsertException e) {
						inserter.cb.onFailure(e, inserter, container);
					}
					container.deactivate(inserter, 1);
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(null, this);
		}
	}

	/**
	 * Start a new-style site insert. Schedule a job on the database thread if it is persistent, 
	 * otherwise start it immediately.
	 * @param inserter The request to start.
	 * @throws InsertException If the insert is transient and failed to start.
	 * @throws DatabaseDisabledException If the insert is persistent and the database is disabled.
	 */
	public void start(final BaseManifestPutter inserter) throws InsertException, DatabaseDisabledException {
		if(inserter.persistent()) {
			jobRunner.queue(new DBJob() {
				
				public boolean run(ObjectContainer container, ClientContext context) {
					container.activate(inserter, 1);
					try {
						inserter.start(container, context);
					} catch (InsertException e) {
						inserter.cb.onFailure(e, inserter, container);
					}
					container.deactivate(inserter, 1);
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY, false);
		} else {
			inserter.start(null, this);
		}
	}

	/**
	 * Get the temporary bucket factory appropriate for a request.
	 * @param persistent If true, get the persistent temporary bucket factory. This creates buckets which 
	 * persist across restarts of the node. If false, get the temporary bucket factory, which creates buckets
	 * which will be deleted once the node is restarted.
	 */
	public BucketFactory getBucketFactory(boolean persistent) {
		if(persistent)
			return persistentBucketFactory;
		else
			return tempBucketFactory;
	}

	/**
	 * Get the RequestScheduler responsible for the given key type. This is used to queue low level requests.
	 * @param ssk If true, get the SSK request scheduler. If false, get the CHK request scheduler.
	 */
	public RequestScheduler getFetchScheduler(boolean ssk, boolean realTime) {
		if(ssk) return realTime ? sskFetchSchedulerRT : sskFetchSchedulerBulk;
		return realTime ? chkFetchSchedulerRT : chkFetchSchedulerBulk;
	}
	
	/** Tell db4o never to store the ClientContext in the database. If it did it would pull in all sorts of
	 * stuff and we end up persisting the entire node, which is both dangerous and expensive. */
	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientContext in database", new Exception("error"));
		return false;
	}

	/** Set the FEC queue after startup e.g. late startup when the database is encrypted. */
	public void setFECQueue(FECQueue fecQueue2) {
		this.fecQueue = fecQueue2;
	}

	/** Set the persistent bucket factories after pulling them from the database. Normally called after
	 * a late database startup e.g. when the database is encrypted.
	 * @param persistentTempBucketFactory The persistent temporary bucket factory.
	 * @param persistentFilenameGenerator The filename generator underlying the persistent temporary bucket factory.
	 * This generates filenames, remembers the directory where the files are, etc.
	 */
	public void setPersistentBucketFactory(PersistentTempBucketFactory persistentTempBucketFactory, FilenameGenerator persistentFilenameGenerator) {
		this.persistentBucketFactory = persistentTempBucketFactory;
		this.persistentFG = persistentFilenameGenerator;
	}

	public void postUserAlert(final UserAlert alert) {
		if(alerts == null) {
			// Wait until after startup
			ticker.queueTimedJob(new Runnable() {

				public void run() {
					alerts.register(alert);
				}
				
			}, "Post alert", 0L, false, false);
		} else {
			alerts.register(alert);
		}
	}

	public void setDownloadCache(DownloadCache cache) {
		this.downloadCache = cache;
	}
	
}
