/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.crypt.RandomSource;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Parallel scheduler structures for non-persistent requests.
 * @author toad
 */
class ClientRequestSchedulerNonPersistent extends ClientRequestSchedulerBase {
	
	private boolean logMINOR;
	
	ClientRequestSchedulerNonPersistent(ClientRequestScheduler sched, boolean forInserts, boolean forSSKs, boolean forRT, RandomSource random) {
		super(forInserts, forSSKs, forRT, random, sched, null);
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
	}

	@Override
	boolean persistent() {
		return false;
	}

}
