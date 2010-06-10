/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.LinkedList;
import java.util.List;

import com.db4o.ObjectContainer;

import freenet.crypt.RandomSource;
import freenet.node.BaseSendableGet;
import freenet.support.Logger;
import freenet.support.Logger.LoggerPriority;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	private boolean logMINOR;
	
	protected final List<BaseSendableGet>recentSuccesses;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched, boolean forInserts, boolean forSSKs, RandomSource random) {
		super(forInserts, forSSKs, random);
		this.sched = sched;
		if(!forInserts)
			recentSuccesses = new LinkedList<BaseSendableGet>();
		else
			recentSuccesses = null;
		logMINOR = Logger.shouldLog(LoggerPriority.MINOR, this);
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
		if(succeeded.isEmpty(container)) return;
			if(logMINOR)
				Logger.minor(this, "Recording successful fetch from "+succeeded);
			recentSuccesses.add(succeeded);
			while(recentSuccesses.size() > 8)
				recentSuccesses.remove(0);
	}

	public boolean objectCanNew(ObjectContainer container) {
		Logger.error(this, "Not storing ClientRequestSchedulerNonPersistent in database", new Exception("error"));
		return false;
	}
	
	
}
