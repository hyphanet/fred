/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * A PluginTalker which has a sendBlocking() function which directly returns the result of the FCP call to the caller.
 * This can be used to simplify code which uses FCP very much, especially UI code which needs the result of FCP calls directly.
 * 
 * @author xor
 */
public class PluginTalkerBlocking extends PluginTalker {
	
	public PluginTalkerBlocking(FredPluginTalker myPluginTalker, Node myNode, String myPluginName, String myConnectionIdentifier)
		throws PluginNotFoundException {
		super(myPluginTalker, myNode, myPluginName, myConnectionIdentifier);
		// TODO Auto-generated constructor stub
	}

	public static class Result {
		public SimpleFieldSet params;
		public Bucket data;
		
		public Result(SimpleFieldSet myParams, Bucket myData) {
			params = myParams;
			data = myData;
		}
	}
	
	protected class PluginReplySenderBlocking extends PluginReplySender {

		protected final PluginReplySender nonBlockingReplySender;
		protected volatile Result mResult;

		public PluginReplySenderBlocking() {
			super(pluginName, connectionIdentifier);
			nonBlockingReplySender = replysender;
		}

		@Override
		public synchronized void send(SimpleFieldSet params, Bucket bucket) {
			if(mResult == null) {
				mResult = new Result(params, bucket);
				notifyAll();
			} else {
				Logger.error(this, "PluginTalkerBlocking is being used with a FCP call which results in more than 1 reply");
				nonBlockingReplySender.send(params, bucket);
			}
		}

		public Result getResult() {
			while(mResult == null) {
				try {
					wait();
				} catch (InterruptedException e) {
				}
			}
			
			return mResult;
		}

	}
	
	/**
	 * When using sendBlocking(), please make sure that you only ever call it for FCP functions which only send() a single result!
	 * Results which are sent by the plugin after the first result are dispatched to the asynchronous onReply() function of your
	 * FredPluginTalker, however this behavior is deprecated and not guranteed to work.
	 */
	public Result sendBlocking(final SimpleFieldSet plugparams, final Bucket data2) {
		final PluginReplySenderBlocking replySender = new PluginReplySenderBlocking();
		
		node.executor.execute(new Runnable() {

			public void run() {
					plugin.handle(replySender, plugparams, data2, access);
			}
		}, "PluginTalkerBlocking " + connectionIdentifier);
		
		return replySender.getResult();
	}

}
