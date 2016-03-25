/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.LowLevelGetException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * All the keys at a given priority which we have received key offers from other nodes for.
 * 
 * This list needs to be kept up to date when:
 * - A request is removed.
 * - A request's priority changes.
 * - A key is found.
 * - A node disconnects or restarts (through the BlockOffer objects on the FailureTable).
 * 
 * And of course, when an offer is received, we need to add an element.
 * 
 * @author toad
 *
 */
@SuppressWarnings("serial") // We don't serialize this.
public class OfferedKeysList extends LowLevelKeyFetcher implements RequestClient {

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	OfferedKeysList(NodeClientCore core, RandomSource random, short priorityClass, boolean isSSK, boolean realTimeFlag) {
	    super(core, random, priorityClass, isSSK, realTimeFlag);
	}
	
    protected void requestSucceeded(Key key, RequestScheduler sched) {
        // We don't use ChosenBlockImpl so have to remove the keys from the fetching set ourselves.
        sched.removeFetchingKey(key);
        sched.wakeStarter();
    }

    protected void requestFailed(Key key, RequestScheduler sched, LowLevelGetException e) {
        // We don't use ChosenBlockImpl so have to remove the keys from the fetching set ourselves.
        sched.removeFetchingKey(key);
        // Something might be waiting for a request to complete (e.g. if we have two requests for the same key), 
        // so wake the starter thread.
        sched.wakeStarter();
    }

    @Override
    public ClientRequester getClientRequest() {
        // Safe here because OfferedKeysList isn't actually registered.
        return null;
    }

}
