/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.query.Query;

import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * @author toad
 * A persistent class that functions as the core of the ClientRequestScheduler.
 * Does not refer to any non-persistable classes as member variables: Node must always
 * be passed in if we need to use it!
 */
// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class ClientRequestSchedulerCore extends ClientRequestSchedulerBase {

	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final PersistentCooldownQueue persistentCooldownQueue;

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * Fetch a ClientRequestSchedulerCore from the database, or create a new one.
	 * @param node
	 * @param forInserts
	 * @param forSSKs
	 * @param selectorContainer
	 * @param executor
	 * @return
	 */
	public static ClientRequestSchedulerCore create(Node node, final boolean forInserts, final boolean forSSKs, final boolean forRT, final long nodeDBHandle, ObjectContainer selectorContainer, long cooldownTime, PrioritizedSerialExecutor databaseExecutor, ClientRequestScheduler sched, ClientContext context) {
		if(selectorContainer == null) {
			return null;
		}
		ObjectSet<ClientRequestSchedulerCore> results = selectorContainer.query(new Predicate<ClientRequestSchedulerCore>() {
			final private static long serialVersionUID = -7517827015509774396L;
			@Override
			public boolean match(ClientRequestSchedulerCore core) {
				if(core.nodeDBHandle != nodeDBHandle) return false;
				if(core.isInsertScheduler != forInserts) return false;
				if(core.isSSKScheduler != forSSKs) return false;
				if(core.isRTScheduler != forRT) return false;
				return true;
			}
		});
		ClientRequestSchedulerCore core;
		if(results.hasNext()) {
			core = results.next();
			selectorContainer.activate(core, 2);
			System.err.println("Loaded core...");
			if(core.nodeDBHandle != nodeDBHandle) throw new IllegalStateException("Wrong nodeDBHandle");
			if(core.isInsertScheduler != forInserts) throw new IllegalStateException("Wrong isInsertScheduler");
			if(core.isSSKScheduler != forSSKs) throw new IllegalStateException("Wrong forSSKs");
		} else {
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, forRT, selectorContainer, cooldownTime);
			selectorContainer.store(core);
			System.err.println("Created new core...");
		}
		core.onStarted(selectorContainer, cooldownTime, sched, context);
		return core;
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, boolean forRT, ObjectContainer selectorContainer, long cooldownTime) {
		super(forInserts, forSSKs, forRT, node.random);
		this.nodeDBHandle = node.nodeDBHandle;
		if(!forInserts) {
			this.persistentCooldownQueue = new PersistentCooldownQueue();
		} else {
			this.persistentCooldownQueue = null;
		}
		this.globalSalt = null;
	}

	private final byte[] globalSalt;

	private void onStarted(ObjectContainer container, long cooldownTime, ClientRequestScheduler sched, ClientContext context) {
		super.onStarted(container, context);
		System.err.println("insert scheduler: "+isInsertScheduler);
		if(!isInsertScheduler) {
			persistentCooldownQueue.setCooldownTime(cooldownTime);
		}
		this.sched = sched;
		hintGlobalSalt(globalSalt);
		// We DO NOT want to rerun the query after consuming the initial set...
		if(isInsertScheduler) {
		preRegisterMeRunner = new DBJob() {

			@Override
			public boolean run(ObjectContainer container, ClientContext context) {
				synchronized(ClientRequestSchedulerCore.this) {
					if(registerMeSet != null) return false;
				}
				long tStart = System.currentTimeMillis();
				// FIXME REDFLAG EVIL DB4O BUG!!!
				// FIXME verify and file a bug
				// This code doesn't check the first bit!
				// I think this is related to the comparator...
//				registerMeSet = container.query(new Predicate() {
//					public boolean match(RegisterMe reg) {
//						if(reg.core != ClientRequestSchedulerCore.this) return false;
//						if(reg.key.addedTime > initTime) return false;
//						return true;
//					}
//				}, new Comparator() {
//
//					public int compare(Object arg0, Object arg1) {
//						RegisterMe reg0 = (RegisterMe) arg0;
//						RegisterMe reg1 = (RegisterMe) arg1;
//						RegisterMeSortKey key0 = reg0.key;
//						RegisterMeSortKey key1 = reg1.key;
//						return key0.compareTo(key1);
//					}
//
//				});
				ObjectSet results = null;
				for(int i=RequestStarter.MAXIMUM_PRIORITY_CLASS;i<=RequestStarter.MINIMUM_PRIORITY_CLASS;i++) {
					Query query = container.query();
					query.constrain(RegisterMe.class);
					query.descend("core").constrain(ClientRequestSchedulerCore.this).and(query.descend("priority").constrain(i));
					results = query.execute();
					if(results.hasNext()) {
						break;
					} else results = null;
				}
				if(results == null)
					return false;
				// This throws NotSupported.
//				query.descend("core").constrain(this).identity().
//					and(query.descend("key").descend("addedTime").constrain(new Long(initTime)).smaller());
				/**
				 * FIXME DB4O
				 * db4o says it has indexed core. But then when we try to query, it produces a diagnostic
				 * suggesting we index it. And of course the query takes ages and uses tons of RAM. So don't
				 * try to filter by core at this point, deal with that later.
				 */
//				query.descend("core").constrain(ClientRequestSchedulerCore.this);
//				Evaluation eval = new Evaluation() {
//
//					public void evaluate(Candidate candidate) {
//						RegisterMe reg = (RegisterMe) candidate.getObject();
//						if(reg.key.addedTime > initTime || reg.core != ClientRequestSchedulerCore.this) {
//							candidate.include(false);
//							candidate.objectContainer().deactivate(reg.key, 1);
//							candidate.objectContainer().deactivate(reg, 1);
//						} else {
//							candidate.include(true);
//						}
//					}
//
//				};
//				query.constrain(eval);
//				query.descend("key").descend("priority").orderAscending();
//				query.descend("key").descend("addedTime").orderAscending();
				synchronized(ClientRequestSchedulerCore.this) {
					registerMeSet = results;
				}
			long tEnd = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "RegisterMe query took "+(tEnd-tStart)+" hasNext="+registerMeSet.hasNext()+" for insert="+isInsertScheduler+" ssk="+isSSKScheduler);
//				if(logMINOR)
//					Logger.minor(this, "RegisterMe query returned: "+registerMeSet.size());
				boolean boost = ClientRequestSchedulerCore.this.sched.isQueueAlmostEmpty();

				try {
					context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 1 : 0), true);
				} catch (DatabaseDisabledException e) {
					// Do nothing, persistence is disabled
				}
				return false;
			}
		};
		registerMeRunner = new RegisterMeRunner();
		}
	}

	private transient DBJob preRegisterMeRunner;

	void start(DBJobRunner runner) {
		startRegisterMeRunner(runner);
	}

	private void startRegisterMeRunner(DBJobRunner runner) {
		if(isInsertScheduler)
			try {
				runner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
			} catch (DatabaseDisabledException e) {
				// Persistence is disabled
			}
	}

	@Override
	boolean persistent() {
		return true;
	}

	private transient ObjectSet registerMeSet;

	private transient RegisterMeRunner registerMeRunner;

	class RegisterMeRunner implements DBJob {

		@Override
		public boolean run(ObjectContainer container, ClientContext context) {
			if(sched.databaseExecutor.getQueueSize(NativeThread.NORM_PRIORITY) > 100) {
				// If the queue isn't empty, reschedule at NORM-1, wait for the backlog to clear
				if(!sched.isQueueAlmostEmpty()) {
					try {
						context.jobRunner.queue(registerMeRunner, NativeThread.NORM_PRIORITY-1, false);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return false;
				}
			}
			long deadline = System.currentTimeMillis() + 10*1000;
			if(registerMeSet == null) {
				Logger.error(this, "registerMeSet is null for "+ClientRequestSchedulerCore.this+" ( "+this+" )");
				return false;
			}
			for(int i=0;i < 1000; i++) {
				try {
					if(!registerMeSet.hasNext()) break;
				} catch (NullPointerException t) {
					Logger.error(this, "DB4O thew NPE in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					try {
						context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return true;
				} catch (ClassCastException t) {
					// WTF?!?!?!?!?!
					Logger.error(this, "DB4O thew ClassCastException in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					try {
						context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
					return true;
				}
				long startNext = System.currentTimeMillis();
				RegisterMe reg = (RegisterMe) registerMeSet.next();
				container.activate(reg, 1);
				if(reg.bootID == context.bootID) {
					if(logMINOR) Logger.minor(this, "Not registering block "+reg+" as was added to the queue");
					continue;
				}
				// FIXME remove the leftover/old core handling at some point, an NPE is acceptable long-term.
				if(reg.core != ClientRequestSchedulerCore.this) {
					if(!container.ext().isStored(reg)) {
						if(logMINOR) Logger.minor(this, "Already deleted RegisterMe "+reg+" - skipping");
						continue;
					}
					if(reg.core == null) {
						Logger.error(this, "Leftover RegisterMe "+reg+" : core already deleted. THIS IS AN ERROR unless you have seen \"Old core not active\" messages before this point.");
						container.delete(reg);
						continue;
					}
					if(!container.ext().isActive(reg.core)) {
						Logger.error(this, "Old core not active in RegisterMe "+reg+" - duplicated cores????");
						container.delete(reg.core);
						container.delete(reg);
						continue;
					}
					if(logMINOR)
						Logger.minor(this, "Ignoring RegisterMe "+reg+" as doesn't belong to me: my insert="+isInsertScheduler+" my ssk="+isSSKScheduler+" his insert="+reg.core.isInsertScheduler+" his ssk="+reg.core.isSSKScheduler);
					container.deactivate(reg, 1);
					continue; // Don't delete.
				}
//				if(reg.key.addedTime > initTime) {
//					if(logMINOR) Logger.minor(this, "Ignoring RegisterMe as created since startup");
//					container.deactivate(reg.key, 1);
//					container.deactivate(reg, 1);
//					continue; // Don't delete
//				}
				if(logMINOR)
					Logger.minor(this, "Running RegisterMe "+reg+" for "+reg.nonGetRequest+" : "+reg.addedTime+" : "+reg.priority);
				// Don't need to activate, fields should exist? FIXME
				if(reg.nonGetRequest != null) {
					container.activate(reg.nonGetRequest, 1);
					if(reg.nonGetRequest.isStorageBroken(container)) {
						String toString = "(throws)";
						try {
							toString = reg.nonGetRequest.toString();
						} catch (Throwable t) {
							// It throws :|
						};
						Logger.error(this, "Stored SingleBlockInserter is broken, maybe leftover from database leakage?: "+toString);
					} else if(reg.nonGetRequest.isCancelled(container)) {
						Logger.normal(this, "RegisterMe: request cancelled: "+reg.nonGetRequest);
					} else {
						if(logMINOR)
							Logger.minor(this, "Registering RegisterMe for insert: "+reg.nonGetRequest);
						sched.registerInsert(reg.nonGetRequest, true, false, container);
					}
					container.delete(reg);
					container.deactivate(reg.nonGetRequest, 1);
				} else {
					// Was trying to register something that has already been deleted.
					// Delete it.
					container.delete(reg);
					container.deactivate(reg, 1);
				}
				if(System.currentTimeMillis() > deadline) break;
			}
			boolean boost = sched.isQueueAlmostEmpty();
			if(registerMeSet.hasNext())
				try {
					context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 1 : 0), true);
				} catch (DatabaseDisabledException e) {
					// Impossible
				}
			else {
				if(logMINOR) Logger.minor(this, "RegisterMeRunner finished");
				synchronized(ClientRequestSchedulerCore.this) {
					registerMeSet = null;
				}
				// Always re-run the query. If there is nothing to register, it won't call back to us.
				preRegisterMeRunner.run(container, context);
			}
			return true;
		}

	}

	public void rerunRegisterMeRunner(DBJobRunner runner) {
		synchronized(this) {
			if(registerMeSet != null) return;
		}
		startRegisterMeRunner(runner);
	}

	@Override
	public synchronized long countQueuedRequests(ObjectContainer container, ClientContext context) {
		long ret = super.countQueuedRequests(container, context);
		long cooldown = persistentCooldownQueue.size(container);
		System.out.println("Cooldown queue size: "+cooldown);
		return ret + cooldown;
	}

}

