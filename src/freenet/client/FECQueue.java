/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client;

import java.util.LinkedList;
import java.util.ListIterator;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Constraint;
import com.db4o.query.Predicate;
import com.db4o.query.Query;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.PrioRunnable;
import freenet.node.RequestStarter;
import freenet.support.Executor;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.NativeThread;

/**
 * The FEC queue. Uses a limited number of threads (at most one per core), a non-persistent queue,
 * a persistent queue (kept in the database), and a transient cache of the persistent queue.
 * Sorted by priority and then by time added.
 * 
 * Note that the FECQueue must be pulled from the database, because FECJob's are queried based
 * on their referring to it.
 * @author toad
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class FECQueue implements OOMHook {
	
	private transient LinkedList<FECJob>[] transientQueue;
	private transient LinkedList<FECJob>[] persistentQueueCache;
	private transient int maxPersistentQueueCacheSize;
	private transient int priorities;
	private transient DBJobRunner databaseJobRunner;
	private transient Executor executor;
	private transient ClientContext clientContext;
	private transient int runningFECThreads;
	private transient int fecPoolCounter;
	private transient PrioRunnable runner;
	private transient DBJob cacheFillerJob;
	private long nodeDBHandle;
	/** If we have delayed startup for the persistent client layer, we will have already created
	 * a FECQueue. Therefore when we load the real one, we will point it to the old FECQueue. */
	private transient FECQueue proxy;
	private transient FECQueue proxiedFor;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
    public static FECQueue create(final long nodeDBHandle, ObjectContainer container, FECQueue transientQueue) {
    	@SuppressWarnings("serial")
		ObjectSet<FECQueue> result = container.query(new Predicate<FECQueue>() {
			@Override
			public boolean match(FECQueue queue) {
				if(queue.nodeDBHandle == nodeDBHandle) return true;
				return false;
			}
		});
		if(result.hasNext()) {
			FECQueue queue = result.next();
			container.activate(queue, 1);
			if(transientQueue != null)
				queue.proxyInit(transientQueue, nodeDBHandle);
			return queue;
		} else {
			FECQueue queue = new FECQueue(nodeDBHandle);
			container.store(queue);
			if(transientQueue != null)
				queue.proxyInit(transientQueue, nodeDBHandle);
			return queue;
		}
	}
	
	public FECQueue(long nodeDBHandle) {
		this.nodeDBHandle = nodeDBHandle;
	}

	public synchronized void proxyInit(FECQueue oldQueue, long dbHandle) {
		this.proxy = oldQueue;
		oldQueue.persistentInit(dbHandle, this);
	}
	
	private void persistentInit(long dbHandle, FECQueue fromDB) {
		this.nodeDBHandle = dbHandle;
		this.proxiedFor = fromDB;
		queueCacheFiller();
	}

	/** Called after creating or deserializing the FECQueue. Initialises all the transient fields. */
	@SuppressWarnings("unchecked")
    public void init(int priorities, int maxCacheSize, DBJobRunner dbJobRunner, Executor exec, ClientContext clientContext) {
		this.priorities = priorities;
		this.maxPersistentQueueCacheSize = maxCacheSize;
		this.databaseJobRunner = dbJobRunner;
		this.executor = exec;
		this.clientContext = clientContext;
		transientQueue = new LinkedList[priorities];
		persistentQueueCache = new LinkedList[priorities];
		for(int i=0;i<priorities;i++) {
			transientQueue[i] = new LinkedList<FECJob>();
			persistentQueueCache[i] = new LinkedList<FECJob>();
		}
		maxRunningFECThreads = getMaxRunningFECThreads();
		OOMHandler.addOOMHook(this);
		initRunner();
		initCacheFillerJob();
		queueCacheFiller();
	}
	
	private void queueCacheFiller() {
		try {
			databaseJobRunner.queue(cacheFillerJob, NativeThread.NORM_PRIORITY, true);
		} catch (DatabaseDisabledException e) {
			// Ok.
		}
	}

	public void addToQueue(FECJob job, FECCodec codec, ObjectContainer container) {
		synchronized(this) {
			if(proxy != null) {
				proxy.addToQueue(job, codec, container);
				return;
			}
		}
		final boolean logMINOR = FECQueue.logMINOR;
		if(logMINOR)
			Logger.minor(StandardOnionFECCodec.class, "Adding a new job to the queue: "+job+".");
		int maxThreads = getMaxRunningFECThreads();
		if(job.persistent) {
			job.activateForExecution(container);
			container.store(job);
		}
		synchronized(this) {
			if(!job.persistent) {
				transientQueue[job.priority].addLast(job);
			} else {
				int totalAbove = 0;
				for(int i=0;i<job.priority;i++) {
					totalAbove += persistentQueueCache[i].size();
				}
				if(totalAbove >= maxPersistentQueueCacheSize) {
					// Don't add.
					if(logMINOR)
						Logger.minor(this, "Not adding persistent job to in-RAM cache, too many above it");
				} else {
					if(totalAbove + persistentQueueCache[job.priority].size() >= maxPersistentQueueCacheSize) {
						// Still don't add, within a priority it's oldest first.
						if(logMINOR)
							Logger.minor(this, "Not adding persistent job to in-RAM cache, too many at same priority");
					} else {
						persistentQueueCache[job.priority].addLast(job);
						int total = totalAbove + persistentQueueCache[job.priority].size();
						for(int i=job.priority+1;i<priorities;i++) {
							total += persistentQueueCache[i].size();
							while(total >= maxPersistentQueueCacheSize && !persistentQueueCache[i].isEmpty()) {
								if(logMINOR)
									Logger.minor(this, "Removing low priority job from cache, total now "+total);
								persistentQueueCache[i].removeLast();
								total--;
							}
						}
					}
				}
			}
			// Do not deactivate the job.
			// Two jobs may overlap in cross-segment decoding, resulting in very bad things.
			// Plus, if we didn't add it to the cache, it will disappear when the parent is deactivated anyway.
			if(runningFECThreads < maxThreads) {
				executor.execute(runner, "FEC Pool(" + (fecPoolCounter++) + ")");
				runningFECThreads++;
			}
			notifyAll();
		}
	}
	
	private void initRunner() {
		runner = new PrioRunnable() {
		/**
		 * Runs on each thread.
		 * @author nextgens
		 */
		@Override
		public void run() {
			freenet.support.Logger.OSThread.logPID(this);
			try {
				while(true) {
					final FECJob job;
					// Get a job
					synchronized (FECQueue.this) {
						job = getFECJobBlockingNoDBAccess();
						if(job == null) {
							// Too many jobs running.
							return;
						}
						if(job.running) {
							Logger.error(this, "Job already running: "+job);
							continue;
						}
						job.running = true;
					}

					if(logMINOR)
						Logger.minor(this, "Running job "+job);
					// Encode it
					try {
						if (job.isADecodingJob)
							job.getCodec().realDecode(job.dataBlockStatus, job.checkBlockStatus, job.blockLength,
							        job.bucketFactory);
						else {
							job.getCodec().realEncode(job.dataBlocks, job.checkBlocks, job.blockLength, job.bucketFactory);
							// Update SplitFileBlocks from buckets if necessary
							if ((job.dataBlockStatus != null) || (job.checkBlockStatus != null)) {
								for (int i = 0; i < job.dataBlocks.length; i++) {
									Bucket existingData = job.dataBlockStatus[i].trySetData(job.dataBlocks[i]);
									if(existingData != null && existingData != job.dataBlocks[i]) {
										job.dataBlocks[i].free();
										job.dataBlocks[i] = null;
									}
								}
								for (int i = 0; i < job.checkBlocks.length; i++) {
									Bucket existingData = job.checkBlockStatus[i].trySetData(job.checkBlocks[i]);
									if(existingData != null && existingData != job.checkBlocks[i]) {
										job.checkBlocks[i].free();
										job.checkBlocks[i] = null;
									}
								}
							}
						}
					} catch (final Throwable t) {
						Logger.error(this, "Caught: "+t, t);
						if(job.persistent) {
							if(logMINOR)
								Logger.minor(this, "Scheduling callback for "+job+" after "+t, t);
							int prio = job.isADecodingJob ? NativeThread.NORM_PRIORITY+1 : NativeThread.NORM_PRIORITY;
							// Run at a fairly high priority so we get the blocks out of memory and onto disk.
							databaseJobRunner.queue(new DBJob() {

								@Override
								public boolean run(ObjectContainer container, ClientContext context) {
									try {
										job.storeBlockStatuses(container, true);
									} catch (Throwable t) {
										Logger.error(this, "Caught storing block statuses for "+job+" : "+t, t);
										// Fail with the original error.
									}
									// Don't activate the job itself.
									// It MUST already be activated, because it is carrying the status blocks.
									// The status blocks have been set on the FEC thread but *not stored* because
									// they can't be stored on the FEC thread.
									Logger.minor(this, "Activating "+job.callback+" is active="+container.ext().isActive(job.callback));
									container.activate(job.callback, 1);
									if(logMINOR)
										Logger.minor(this, "Running callback for "+job);
									try {
										job.callback.onFailed(t, container, context);
									} catch (Throwable t1) {
										Logger.error(this, "Caught "+t1+" in FECQueue callback failure", t1);
									} finally {
										// Always delete the job, even if the callback throws.
										container.delete(job);
									}
									if(container.ext().isStored(job.callback))
										container.deactivate(job.callback, 1);
									return true;
								}
								
								@Override
								public String toString() {
									return "FECQueueJobFailedCallback@"+Integer.toHexString(super.hashCode());
								}
								
							}, prio, false);
							if(logMINOR)
								Logger.minor(this, "Scheduled callback for "+job+"...");
						} else {
							job.callback.onFailed(t, null, clientContext);
						}
						continue; // Try the next one.
					}

					// Call the callback
					try {
						if(!job.persistent) {
							if (job.isADecodingJob)
								job.callback.onDecodedSegment(null, clientContext, job, job.dataBlocks, job.checkBlocks, job.dataBlockStatus, job.checkBlockStatus);
							else
								job.callback.onEncodedSegment(null, clientContext, job, job.dataBlocks, job.checkBlocks, job.dataBlockStatus, job.checkBlockStatus);
						} else {
							if(logMINOR)
								Logger.minor(this, "Scheduling callback for "+job+"...");
							int prio = job.isADecodingJob ? NativeThread.NORM_PRIORITY+1 : NativeThread.NORM_PRIORITY;
							if(job.priority > RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS)
								prio--;
							if(job.priority >= RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS)
								prio--;
							databaseJobRunner.queue(new DBJob() {

								@Override
								public boolean run(ObjectContainer container, ClientContext context) {
									try {
										job.storeBlockStatuses(container, false);
									} catch (Throwable t) {
										Logger.error(this, "Caught storing block statuses on "+this+" : "+t, t);
										// Don't activate the job itself.
										// It MUST already be activated, because it is carrying the status blocks.
										// The status blocks have been set on the FEC thread but *not stored* because
										// they can't be stored on the FEC thread.
										Logger.minor(this, "Activating "+job.callback+" is active="+container.ext().isActive(job.callback));
										container.activate(job.callback, 1);
										if(logMINOR)
											Logger.minor(this, "Running callback for "+job);
										try {
											job.callback.onFailed(t, container, context);
										} catch (Throwable t1) {
											Logger.error(this, "Caught "+t1+" in FECQueue callback failure", t1);
										} finally {
											// Always delete the job, even if the callback throws.
											container.delete(job);
										}
										if(container.ext().isStored(job.callback))
											container.deactivate(job.callback, 1);
										return true;
									}
									// Don't activate the job itself.
									// It MUST already be activated, because it is carrying the status blocks.
									// The status blocks have been set on the FEC thread but *not stored* because
									// they can't be stored on the FEC thread.
									Logger.minor(this, "Activating "+job.callback+" is active="+container.ext().isActive(job.callback));
									container.activate(job.callback, 1);
									if(logMINOR)
										Logger.minor(this, "Running callback for "+job);
									try {
									if(job.isADecodingJob)
										job.callback.onDecodedSegment(container, clientContext, job, job.dataBlocks, job.checkBlocks, job.dataBlockStatus, job.checkBlockStatus);
									else
										job.callback.onEncodedSegment(container, clientContext, job, job.dataBlocks, job.checkBlocks, job.dataBlockStatus, job.checkBlockStatus);
									} catch (Throwable t) {
										Logger.error(this, "Caught "+t+" in FECQueue callback", t);
									} finally {
										// Always delete the job, even if the callback throws.
										container.delete(job);
									}
									if(container.ext().isStored(job.callback))
										container.deactivate(job.callback, 1);
									return true;
								}
								
								@Override
								public String toString() {
									return "FECQueueJobCompletedCallback@"+Integer.toHexString(super.hashCode());
								}
								
							}, prio, false);
							if(logMINOR)
								Logger.minor(this, "Scheduled callback for "+job+"...");
							
						}
					} catch (Throwable e) {
						Logger.error(this, "The callback failed!" + e, e);
					}
				}
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t+" in "+this, t);
			}
			finally {
				synchronized (FECQueue.this) {
					runningFECThreads--;
				}
			}
		}

		@Override
		public int getPriority() {
			return NativeThread.LOW_PRIORITY;
		}

	};
	}

	private void initCacheFillerJob() {
		cacheFillerJob = new DBJob() {
			
		@Override
		public String toString() {
			return "FECQueueCacheFiller";
		}

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			// Try to avoid accessing the database while synchronized on the FECQueue.
			if(logMINOR) Logger.minor(this, "Running FEC cache filler job");
			while(true) {
				boolean addedAny = false;
				int totalCached = 0;
				for(short prio=0;prio<priorities;prio++) {
					int grab = 0;
					synchronized(FECQueue.this) {
						int newCached = totalCached + persistentQueueCache[prio].size();
						if(newCached >= maxPersistentQueueCacheSize) return false;
						grab = maxPersistentQueueCacheSize - newCached;
						totalCached = newCached;
					}
					if(logMINOR) Logger.minor(this, "Grabbing up to "+grab+" jobs at priority "+prio);
					Query query = container.query();
					query.constrain(FECJob.class);
					Constraint con = query.descend("priority").constrain(Short.valueOf(prio));
					if(proxiedFor != null)
						con.and(query.descend("queue").constrain(proxiedFor).identity());
					else
						con.and(query.descend("queue").constrain(FECQueue.this).identity());
					query.descend("addedTime").orderAscending();
					@SuppressWarnings("unchecked")
					ObjectSet<FECJob> results = query.execute();
					if(results.hasNext()) {
						for(int j=0;j<grab && results.hasNext();j++) {
							FECJob job = results.next();
							synchronized(FECQueue.this) {
								if(job.running) {
									j--;
									if(logMINOR) Logger.minor(this, "Not adding, already running (1): "+job);
									continue;
								}
							}
							if(!job.activateForExecution(container)) {
								if(job.callback != null) {
									container.activate(job.callback, 1);
									try {
										job.callback.onFailed(new NullPointerException("Not all data blocks present"), container, context);
									} catch (Throwable t) {
										try {
											Logger.error(this, "Caught "+t+" while calling failure callback on "+job, t);
										} catch (Throwable t1) {
											// Ignore
										}
									}
									container.delete(job);
								}
								continue;
							}
							if(job.isCancelled(container)) {
								container.delete(job);
								continue;
							}
							if(logMINOR) Logger.minor(this, "Maybe adding "+job);
							synchronized(FECQueue.this) {
								if(job.running) {
									j--;
									if(logMINOR) Logger.minor(this, "Not adding, already running (2): "+job);
									continue;
								}
								if(persistentQueueCache[prio].contains(job)) {
									j--;
									if(logMINOR) Logger.minor(this, "Not adding as on persistent queue cache for "+prio+" : "+job);
									continue;
								}
								boolean added = false;
								for(ListIterator<FECJob> it = persistentQueueCache[prio].listIterator();it.hasNext();) {
									FECJob cmp = it.next();
									if(cmp.addedTime >= job.addedTime) {
										it.previous();
										it.add(job);
										added = true;
										if(logMINOR) Logger.minor(this, "Adding "+job+" before "+it);
										break;
									}
								}
								if(!added) persistentQueueCache[prio].addLast(job);
								if(logMINOR) Logger.minor(this, "Added "+job);
								addedAny = true;
							}
						}
					}
				}
				if(!addedAny) {
					if(logMINOR)
						Logger.minor(this, "No more jobs to add");
					// Don't notify, let it sleep until more jobs are added.
					return false;
				} else {
					synchronized(FECQueue.this) {
						int maxRunningThreads = maxRunningFECThreads;
						if(runningFECThreads < maxRunningThreads) {
							int queueSize = 0;
							for(int i=0;i<priorities;i++) {
								queueSize += persistentQueueCache[i].size();
								if(queueSize + runningFECThreads > maxRunningThreads) break;
							}
							if(queueSize + runningFECThreads < maxRunningThreads)
								maxRunningThreads = queueSize + runningFECThreads;
							while(runningFECThreads < maxRunningThreads) {
								executor.execute(runner, "FEC Pool "+fecPoolCounter++);
								runningFECThreads++;
							}
						}
						FECQueue.this.notifyAll();
					}
				}
			}
		}
		};
		
	}
	
	private int maxRunningFECThreads = -1;

	private synchronized int getMaxRunningFECThreads() {
		if (maxRunningFECThreads != -1)
			return maxRunningFECThreads;
		String osName = System.getProperty("os.name");
		if(osName.indexOf("Windows") == -1 && ((osName.toLowerCase().indexOf("mac os x") > 0) || (!NativeThread.usingNativeCode()))) {
			// OS/X niceness is really weak, so we don't want any more background CPU load than necessary
			// Also, on non-Windows, we need the native threads library to be working.
			maxRunningFECThreads = 1;
		} else {
			// Most other OSs will have reasonable niceness, so go by RAM.
			Runtime r = Runtime.getRuntime();
			int max = r.availableProcessors(); // FIXME this may change in a VM, poll it
			long maxMemory = r.maxMemory();
			if(maxMemory < 256*1024*1024) {
				max = 1;
			} else {
				// Measured 11MB decode 8MB encode on amd64.
				// No more than 10% of available RAM, so 110MB for each extra processor.
				// No more than 3 so that we don't reach any FileDescriptor related limit
				max = Math.min(3, Math.min(max, (int) (Math.min(Integer.MAX_VALUE, maxMemory / (128*1024*1024)))));
			}
			maxRunningFECThreads = max;
		}
		Logger.minor(FECCodec.class, "Maximum FEC threads: "+maxRunningFECThreads);
		return maxRunningFECThreads;
	}

	/**
	 * Find a FEC job to run.
	 * @return null only if there are too many FEC threads running.
	 */
	protected synchronized FECJob getFECJobBlockingNoDBAccess() {
		while(true) {
			if(runningFECThreads > getMaxRunningFECThreads())
				return null;
			for(int i=0;i<priorities;i++) {
				if(!transientQueue[i].isEmpty())
					return transientQueue[i].removeFirst();
				if(!persistentQueueCache[i].isEmpty())
					return persistentQueueCache[i].removeFirst();
			}
			queueCacheFiller();
			try {
				wait();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
	}

	@Override
	public synchronized void handleLowMemory() throws Exception {
		maxRunningFECThreads = Math.max(1, maxRunningFECThreads - 1);
		notify(); // not notifyAll()
	}

	@Override
	public synchronized void handleOutOfMemory() throws Exception {
		maxRunningFECThreads = 1;
		notifyAll();
	}
	
	public void objectOnDeactivate(ObjectContainer container) {
		Logger.error(this, "Attempting to deactivate FECQueue!", new Exception("debug"));
	}

	/**
	 * @param job
	 * @param container
	 * @param context
	 * @return True unless we were unable to remove the job because it has already started.
	 */
	public boolean cancel(FECJob job, ObjectContainer container, ClientContext context) {
		synchronized(this) {
			if(proxy != null) {
				return proxy.cancel(job, container, context);
			}
			for(int i=0;i<priorities;i++) {
				transientQueue[i].remove(job);
				persistentQueueCache[i].remove(job);
			}
		}
		synchronized(job) {
			if(job.running) return false;
		}
		if(job.persistent)
			container.delete(job);
		return true;
	}

	@SuppressWarnings("unchecked")
	public static void dump(ObjectContainer container, int priorities) {
		ObjectSet<FECQueue> queues = container.query(FECQueue.class);
		System.out.println("Queues: "+queues.size());
		for(short prio=0;prio<priorities;prio++) {
			Query query = container.query();
			query.constrain(FECJob.class);
			query.descend("priority").constrain(Short.valueOf(prio));
			ObjectSet<FECJob> results = query.execute();
			System.err.println("FEC jobs at priority "+prio+" : "+results.size());
		}
	}
}
