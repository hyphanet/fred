/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.lang.ref.WeakReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import freenet.node.Node;
import freenet.node.fcp.FCPCallFailedException;
import freenet.node.fcp.FCPConnectionHandler;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author saces, xor
 * 
 */
public class PluginTalker {

	protected Node node;
	protected PluginReplySender replysender;

	protected int access;

	protected WeakReference<FredPluginFCP> pluginRef;
	protected String pluginName;

	public PluginTalker(FredPluginTalker fpt, Node node2, String pluginname2, String connectionIdentifier) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		pluginRef = findPlugin(pluginname2);
		access = FredPluginFCP.ACCESS_DIRECT;
		replysender = new PluginReplySenderDirect(node2, fpt, pluginname2, connectionIdentifier);
	}

	public PluginTalker(Node node2, FCPConnectionHandler handler, String pluginname2, String connectionIdentifier, boolean access2) throws PluginNotFoundException {
		node = node2;
		pluginName = pluginname2;
		pluginRef = findPlugin(pluginname2);
		access = access2 ? FredPluginFCP.ACCESS_FCP_FULL : FredPluginFCP.ACCESS_FCP_RESTRICTED;
		replysender = new PluginReplySenderFCP(handler, pluginname2, connectionIdentifier);
	}

	protected WeakReference<FredPluginFCP> findPlugin(String pluginname2) throws PluginNotFoundException {
		Logger.normal(this, "Searching fcp plugin: " + pluginname2);
		FredPluginFCP plug = node.pluginManager.getFCPPlugin(pluginname2);
		if (plug == null) {
			Logger.error(this, "Could not find fcp plugin: " + pluginname2);
			throw new PluginNotFoundException();
		}
		Logger.normal(this, "Found fcp plugin: " + pluginname2);
		return new WeakReference<FredPluginFCP>(plug);
	}

	public void send(final SimpleFieldSet plugparams, final Bucket data2) {

		node.executor.execute(new Runnable() {

			@Override
			public void run() {
				sendSyncInternalOnly(plugparams, data2);
			}
		}, "FCPPlugin talk runner for " + pluginName);
	}
	
	private void sendSyncInternalOnly(final SimpleFieldSet plugparams, final Bucket data2) {
		try {
			FredPluginFCP plug = pluginRef.get();
			if (plug==null) {
				// FIXME How to get this out to surrounding send(..)?
				// throw new PluginNotFoundException(How to get this out to surrounding send(..)?);
				Logger.error(this, "Connection to plugin '"+pluginName+"' lost.", new Exception("FIXME"));
				return;
			}
			plug.handle(replysender, plugparams, data2, access);
		} catch (ThreadDeath td) {
			throw td;  // Fatal, thread is stop()'ed
		} catch (VirtualMachineError vme) {
			throw vme; // OOM is included here
		} catch (Throwable t) {
			Logger.error(this, "Cought error while execute fcp plugin handler for '"+pluginName+"', report it to the plugin author: " + t.getMessage(), t);
		}
	}

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
                        final FredPluginFCP plug = pluginRef.get();
                        if(plug==null)
                            throw new PluginNotFoundException();
                        
                        // FIXME: plug is effectively equal to "node.pluginManager.getFCPPlugin(pluginname2)"
                        // Is the handle() function of something returned from that synchronous?
                        plug.handle(replysender, params, bucket, access);
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
            node.executor.execute(runner, "PluginTalker.sendSynchronous() to " + pluginName);
            while(!runner.finished)
                condition.awaitUninterruptibly(); // May wake up spuriously, which is why we check the runne.finished boolean
            
            if(runner.throwable != null)
              throw new FCPCallFailedException(runner.throwable);
        } finally {
            lock.unlock();
        }
    }
	
}
