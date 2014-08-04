/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.node.Node;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

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
	public static ClientRequestSchedulerCore create(Node node, final boolean forInserts, final boolean forSSKs, final boolean forRT, final long nodeDBHandle, ObjectContainer selectorContainer, long cooldownTime, ClientRequestScheduler sched, ClientContext context) {
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
	}

	@Override
	boolean persistent() {
		return true;
	}

}

