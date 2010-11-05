/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String NAME = "ListPersistentRequests";
	
	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		// Do nothing
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	@Override
	public String getName() {
		return NAME;
	}
	
	public static abstract class ListJob implements DBJob {

		final FCPClient client;
		final FCPConnectionOutputHandler outputHandler;
		boolean sentRestartJobs;
		
		ListJob(FCPClient client, FCPConnectionOutputHandler outputHandler) {
			this.client = client;
			this.outputHandler = outputHandler;
		}
		
		int progressCompleted = 0;
		int progressRunning = 0;
		
		public boolean run(ObjectContainer container, ClientContext context) {
			if(container != null) container.activate(client, 1);
			while(!sentRestartJobs) {
				if(outputHandler.isQueueHalfFull()) {
					if(container != null && !client.isGlobalQueue) container.deactivate(client, 1);
					reschedule(context);
					return false;
				}
				int p = client.queuePendingMessagesOnConnectionRestart(outputHandler, container, progressCompleted, 30);
				if(p <= progressCompleted) {
					sentRestartJobs = true;
					break;
				}
				progressCompleted = p;
			}
			if(noRunning()) {
				complete(container, context);
			}
			while(true) {
				if(outputHandler.isQueueHalfFull()) {
					if(container != null && !client.isGlobalQueue) container.deactivate(client, 1);
					reschedule(context);
					return false;
				}
				int p = client.queuePendingMessagesFromRunningRequests(outputHandler, container, progressRunning, 30);
				if(p <= progressRunning) {
					if(container != null && !client.isGlobalQueue) container.deactivate(client, 1);
					complete(container, context);
					return false;
				}
				progressRunning = p;
			}
		}
		
		abstract void reschedule(ClientContext context);
		
		abstract void complete(ObjectContainer container, ClientContext context);
		
		protected boolean noRunning() {
			return false;
		}
		
	};
	
	public static abstract class TransientListJob extends ListJob implements Runnable {

		final ClientContext context;
		
		TransientListJob(FCPClient client, FCPConnectionOutputHandler handler, ClientContext context) {
			super(client, handler);
			this.context = context;
		}
		
		public void run() {
			run(null, context);
		}

		@Override
		void reschedule(ClientContext context) {
			context.ticker.queueTimedJob(this, 100);
		}
		
	}
	
	public static abstract class PersistentListJob extends ListJob implements DBJob, Runnable {
		
		final ClientContext context;
		
		PersistentListJob(FCPClient client, FCPConnectionOutputHandler handler, ClientContext context) {
			super(client, handler);
			this.context = context;
		}
		
		@Override
		void reschedule(ClientContext context) {
			context.ticker.queueTimedJob(this, 100);
		}
		
		public void run() {
			try {
				context.jobRunner.queue(this, NativeThread.HIGH_PRIORITY-1, false);
			} catch (DatabaseDisabledException e) {
				outputHandler.queue(new EndListPersistentRequestsMessage());
			}
		}
		
	}
	
	@Override
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		
		FCPClient rebootClient = handler.getRebootClient();
		
		TransientListJob job = new TransientListJob(rebootClient, handler.outputHandler, node.clientCore.clientContext) {

			@Override
			void complete(ObjectContainer container, ClientContext context) {
				
				if(handler.getRebootClient().watchGlobal) {
					FCPClient globalRebootClient = handler.server.globalRebootClient;
					
					TransientListJob job = new TransientListJob(globalRebootClient, outputHandler, context) {

						@Override
						void complete(ObjectContainer container,
								ClientContext context) {
							finishComplete(container, context);
						}
						
					};
					job.run();
				} else {
					finishComplete(container, context);
				}
				
			}

			private void finishComplete(ObjectContainer container,
					ClientContext context) {
				try {
					context.jobRunner.queue(new DBJob() {

						public boolean run(ObjectContainer container, ClientContext context) {
							FCPClient foreverClient = handler.getForeverClient(container);
							PersistentListJob job = new PersistentListJob(foreverClient, outputHandler, context) {

								@Override
								void complete(ObjectContainer container,
										ClientContext context) {
									if(handler.getRebootClient().watchGlobal) {
										FCPClient globalForeverClient = handler.server.globalForeverClient;
										PersistentListJob job = new PersistentListJob(globalForeverClient, outputHandler, context) {

											@Override
											void complete(
													ObjectContainer container,
													ClientContext context) {
												finishFinal();
											}
											
										};
										job.run(container, context);
									} else {
										finishFinal();
									}
								}

								private void finishFinal() {
									outputHandler.queue(new EndListPersistentRequestsMessage());
								}
								
							};
							job.run(container, context);
							return false;
						}
					}, NativeThread.HIGH_PRIORITY-1, false);
				} catch (DatabaseDisabledException e) {
					handler.outputHandler.queue(new EndListPersistentRequestsMessage());
				}
			}
			
		};
		job.run();
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
}
