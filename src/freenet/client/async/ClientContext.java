/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.Random;

import freenet.client.ArchiveManager;
import freenet.client.FetchException;
import freenet.client.InsertException;
import freenet.client.filter.LinkFilterExceptionProvider;
import freenet.clients.fcp.FCPPersistentRoot;
import freenet.crypt.RandomSource;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarterGroup;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.DummyJobRunner;
import freenet.support.Executor;
import freenet.support.MemoryLimitedJobRunner;
import freenet.support.Ticker;
import freenet.support.api.BucketFactory;
import freenet.support.compress.RealCompressor;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.LockableRandomAccessThingFactory;
import freenet.support.io.NativeThread;
import freenet.support.io.PersistentFileTracker;
import freenet.support.io.PersistentTempBucketFactory;

/**
 * Object passed in to client-layer operations, containing references to essential but mostly transient 
 * objects such as the schedulers and the FEC queue.
 * @author toad
 */
public class ClientContext {
	
	private transient ClientRequestScheduler sskFetchSchedulerBulk;
	private transient ClientRequestScheduler chkFetchSchedulerBulk;
	private transient ClientRequestScheduler sskInsertSchedulerBulk;
	private transient ClientRequestScheduler chkInsertSchedulerBulk;
	private transient ClientRequestScheduler sskFetchSchedulerRT;
	private transient ClientRequestScheduler chkFetchSchedulerRT;
	private transient ClientRequestScheduler sskInsertSchedulerRT;
	private transient ClientRequestScheduler chkInsertSchedulerRT;
	private transient UserAlertManager alerts;
	/** The main Executor for the node. Jobs for transient requests run here. */
	public transient final Executor mainExecutor;
	/** We need to be able to suspend execution of jobs changing persistent state in order to write
	 * it to disk consistently. Also, some jobs may want to request immediate serialization. */
	public transient final PersistentJobRunner jobRunner;
	public transient final long nodeDBHandle;
	public transient final BackgroundBlockEncoder backgroundBlockEncoder;
	public transient final RandomSource random;
	public transient final ArchiveManager archiveManager;
	public transient final PersistentTempBucketFactory persistentBucketFactory;
	public transient PersistentFileTracker persistentFileTracker;
	public transient final BucketFactory tempBucketFactory;
	public transient final LockableRandomAccessThingFactory tempRAFFactory;
	public transient final LockableRandomAccessThingFactory persistentRAFFactory;
	public transient final HealingQueue healingQueue;
	public transient final USKManager uskManager;
	public transient final Random fastWeakRandom;
	public transient final long bootID;
	public transient final Ticker ticker;
	public transient final FilenameGenerator fg;
	public transient final FilenameGenerator persistentFG;
	public transient final InsertCompressorTracker persistentInsertCompressors;
	public transient final InsertCompressorTracker transientInsertCompressors;
	public transient final RealCompressor rc;
	public transient final DatastoreChecker checker;
	public transient final CooldownTracker cooldownTracker;
	public transient DownloadCache downloadCache;
	/** Used for memory intensive jobs such as in-RAM FEC decodes. Some of these jobs may do disk 
	 * I/O and we don't guarantee to serialise them. The new splitfile code does FEC decodes 
	 * entirely in memory, which saves a lot of seeks and improves robustness. */
	public transient final MemoryLimitedJobRunner memoryLimitedJobRunner;
	public transient final FCPPersistentRoot persistentRoot;

	/** Provider for link filter exceptions. */
	public transient final LinkFilterExceptionProvider linkFilterExceptionProvider;
	/** Transient version of the PersistentJobRunner, just starts stuff immediately. Helpful for
	 * avoiding having two different API's, e.g. in SplitFileFetcherStorage. */
    public PersistentJobRunner dummyJobRunner;

	public ClientContext(long bootID, long nodeDBHandle, PersistentJobRunner jobRunner, Executor mainExecutor,
			BackgroundBlockEncoder blockEncoder, ArchiveManager archiveManager,
			PersistentTempBucketFactory ptbf, BucketFactory tbf, PersistentFileTracker tracker,
			InsertCompressorTracker transientInsertCompressors, InsertCompressorTracker persistentInsertCompressors,
			HealingQueue hq, USKManager uskManager, RandomSource strongRandom,
			Random fastWeakRandom, Ticker ticker, FilenameGenerator fg, FilenameGenerator persistentFG,
			LockableRandomAccessThingFactory rafFactory, LockableRandomAccessThingFactory persistentRAFFactory,
			RealCompressor rc, DatastoreChecker checker, FCPPersistentRoot persistentRoot,
			LinkFilterExceptionProvider linkFilterExceptionProvider, long memoryLimitedJobRunnerMemoryLimit) {
		this.bootID = bootID;
		this.jobRunner = jobRunner;
		this.mainExecutor = mainExecutor;
		this.nodeDBHandle = nodeDBHandle;
		this.backgroundBlockEncoder = blockEncoder;
		this.random = strongRandom;
		this.archiveManager = archiveManager;
		this.persistentBucketFactory = ptbf;
		this.persistentFileTracker = ptbf;
		this.tempBucketFactory = tbf;
		this.healingQueue = hq;
		this.transientInsertCompressors = transientInsertCompressors;
		this.persistentInsertCompressors = persistentInsertCompressors;
		this.uskManager = uskManager;
		this.fastWeakRandom = fastWeakRandom;
		this.ticker = ticker;
		this.fg = fg;
		this.persistentFG = persistentFG;
		this.persistentRAFFactory = persistentRAFFactory;
		this.rc = rc;
		this.checker = checker;
		this.linkFilterExceptionProvider = linkFilterExceptionProvider;
		this.cooldownTracker = new CooldownTracker();
		this.memoryLimitedJobRunner = new MemoryLimitedJobRunner(memoryLimitedJobRunnerMemoryLimit, mainExecutor);
		this.tempRAFFactory = rafFactory; 
		this.persistentRoot = persistentRoot;
		this.dummyJobRunner = new DummyJobRunner(mainExecutor, this);
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
	public void start(final ClientPutter inserter, final boolean earlyEncode) throws InsertException, PersistenceDisabledException {
		if(inserter.persistent()) {
			jobRunner.queue(new PersistentJob() {
				
				@Override
				public boolean run(ClientContext context) {
					try {
						inserter.start(earlyEncode, false, context);
					} catch (InsertException e) {
						inserter.client.onFailure(e, inserter);
					}
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY);
		} else {
			inserter.start(earlyEncode, false, this);
		}
	}

	/**
	 * Start a request. Schedule a job on the database thread if it is persistent, otherwise start it 
	 * immediately.
	 * @param getter The request to start.
	 * @throws FetchException If the request is transient and failed to start.
	 * @throws DatabaseDisabledException If the request is persistent and the database is disabled.
	 */
	public void start(final ClientGetter getter) throws FetchException, PersistenceDisabledException {
		if(getter.persistent()) {
			jobRunner.queue(new PersistentJob() {
				
				@Override
				public boolean run(ClientContext context) {
					try {
						getter.start(context);
					} catch (FetchException e) {
						getter.clientCallback.onFailure(e, getter);
					}
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY);
		} else {
			getter.start(this);
		}
	}

	/**
	 * Start a new-style site insert. Schedule a job on the database thread if it is persistent, 
	 * otherwise start it immediately.
	 * @param inserter The request to start.
	 * @throws InsertException If the insert is transient and failed to start.
	 * @throws DatabaseDisabledException If the insert is persistent and the database is disabled.
	 */
	public void start(final BaseManifestPutter inserter) throws InsertException, PersistenceDisabledException {
		if(inserter.persistent()) {
			jobRunner.queue(new PersistentJob() {
				
				@Override
				public boolean run(ClientContext context) {
					try {
						inserter.start(context);
					} catch (InsertException e) {
						inserter.cb.onFailure(e, inserter);
					}
					return true;
				}
				
			}, NativeThread.NORM_PRIORITY);
		} else {
			inserter.start(this);
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
	
	public void postUserAlert(final UserAlert alert) {
		if(alerts == null) {
			// Wait until after startup
			ticker.queueTimedJob(new Runnable() {

				@Override
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

    public InsertCompressorTracker getInsertCompressors(boolean persistent) {
        return persistent ? persistentInsertCompressors : transientInsertCompressors;
    }
	
}
