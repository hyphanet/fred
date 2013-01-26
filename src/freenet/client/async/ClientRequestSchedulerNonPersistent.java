/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.ArrayDeque;
import java.util.Deque;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.node.BaseSendableGet;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	private boolean logMINOR;
	
	protected final Deque<BaseSendableGet>recentSuccesses;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched, boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random) {
		super(forInserts, forSSKs, forRT, random);
		this.sched = sched;
		if(!forInserts)
			recentSuccesses = new ArrayDeque<BaseSendableGet>();
		else
			recentSuccesses = null;
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	@Override
	boolean persistent() {
		return false;
	}

	ObjectContainer container() {
		return null;
	}

	@Override
	public void succeeded(BaseSendableGet succeeded, ObjectContainer container) {
		// Do nothing.
		// FIXME: Keep a list of recently succeeded ClientRequester's.
		if(isInsertScheduler) return;
		if(persistent()) {
			container.activate(succeeded, 1);
		}
		if(succeeded.isCancelled(container)) return;
		// Don't bother with getCooldownTime at this point.
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+succeeded);
		synchronized(recentSuccesses) {
			while(recentSuccesses.size() >= 8)
				recentSuccesses.pollFirst();
			recentSuccesses.add(succeeded);
		}
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientRequestSchedulerNonPersistent in database", new Exception("error"));
		return false;
	}
	
	
}
