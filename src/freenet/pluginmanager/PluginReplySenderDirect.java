/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import freenet.node.Node;
import freenet.node.fcp.FCPCallFailedException;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces
 *
 */
public class PluginReplySenderDirect extends PluginReplySender {
	
	private final Node node;
	private final FredPluginTalker target;

	public PluginReplySenderDirect(Node node2, FredPluginTalker target2, String pluginname2, String identifier2) {
		super(pluginname2, identifier2);
		node = node2;
		target = target2;
	}

	@Override
	public void send(final SimpleFieldSet params, final Bucket bucket) {
		
		node.executor.execute(new Runnable() {

			@Override
			public void run() {

				try {
					target.onReply(pluginname, identifier, params, bucket);
				} catch (Throwable t) {
					Logger.error(this, "Cought error while handling plugin reply: " + t.getMessage(), t);
				}

			}
		}, "FCPPlugin reply runner for " + pluginname);
	}

    @Override
    public void sendSynchronous(final SimpleFieldSet params, final Bucket bucket) throws FCPCallFailedException {
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();

        lock.lock();
        try {
            class Runner implements Runnable {
                volatile boolean finished = false;
                Throwable throwable = null;
                
                @Override
                public void run() {
                    lock.lock();
                    try {
                        target.onReply(pluginname, identifier, params, bucket);
                    } catch (Throwable t) {
                        throwable = t; 
                    } finally {
                        finished = true;
                        condition.signal();
                        lock.unlock();
                    }
                }
            };
            
            final Runner runner = new Runner();
            node.executor.execute(runner, "FCPPlugin reply runner for " + pluginname);
            while(!runner.finished)
                condition.awaitUninterruptibly(); // May wake up spuriously, which is why we check the runne.finished boolean
            
            if(runner.throwable != null)
              throw new FCPCallFailedException(runner.throwable);
        } finally {
            lock.unlock();
        }
    }
}
