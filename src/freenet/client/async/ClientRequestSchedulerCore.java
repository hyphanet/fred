/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

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

	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, boolean forRT, long cooldownTime, ClientRequestScheduler sched) {
		super(forInserts, forSSKs, forRT, node.random, sched);
		this.nodeDBHandle = node.nodeDBHandle;
		this.globalSalt = null;
	}

	@Override
	boolean persistent() {
		return true;
	}

}

