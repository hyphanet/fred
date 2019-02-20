/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import freenet.client.async.ClientContext;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;
import freenet.support.io.NativeThread;

public class ListPersistentRequestsMessage extends FCPMessage {

	static final String NAME = "ListPersistentRequests";
	private final String identifier;

	public ListPersistentRequestsMessage(SimpleFieldSet fs) {
		identifier = fs.get("Identifier");
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	@Override
	public String getName() {
		return NAME;
	}
	
	public static abstract class ListJob implements PersistentJob {

		final PersistentRequestClient client;
		final FCPConnectionOutputHandler outputHandler;
		protected final String listRequestIdentifier;
		boolean sentRestartJobs;

		ListJob(PersistentRequestClient client, FCPConnectionOutputHandler outputHandler, String listRequestIdentifier) {
			this.client = client;
			this.outputHandler = outputHandler;
			this.listRequestIdentifier = listRequestIdentifier;
		}
		
		int progressCompleted = 0;
		int progressRunning = 0;
		
		@Override
		public boolean run(ClientContext context) {
			while(!sentRestartJobs) {
				if(outputHandler.isQueueHalfFull()) {
					reschedule(context);
					return false;
				}
				int p = client.queuePendingMessagesOnConnectionRestart(outputHandler, listRequestIdentifier, progressCompleted, 30);
				if(p <= progressCompleted) {
					sentRestartJobs = true;
					break;
				}
				progressCompleted = p;
			}
			if(noRunning()) {
				complete(context);
			}
			while(true) {
				if(outputHandler.isQueueHalfFull()) {
					reschedule(context);
					return false;
				}
				int p = client.queuePendingMessagesFromRunningRequests(outputHandler, listRequestIdentifier, progressRunning, 30);
				if(p <= progressRunning) {
					complete(context);
					return false;
				}
				progressRunning = p;
			}
		}
		
		abstract void reschedule(ClientContext context);
		
		abstract void complete(ClientContext context);
		
		protected boolean noRunning() {
			return false;
		}
		
	};
	
	public static abstract class TransientListJob extends ListJob implements Runnable {

		final ClientContext context;

		TransientListJob(PersistentRequestClient client, FCPConnectionOutputHandler handler, ClientContext context, String listRequestIdentifier) {
			super(client, handler, listRequestIdentifier);
			this.context = context;
		}
		
		@Override
		public void run() {
			run(context);
		}

		@Override
		void reschedule(ClientContext context) {
			context.ticker.queueTimedJob(this, 100);
		}
		
	}
	
	public static abstract class PersistentListJob extends ListJob implements PersistentJob, Runnable {
		
		final ClientContext context;

		PersistentListJob(PersistentRequestClient client, FCPConnectionOutputHandler handler, ClientContext context, String listRequestIdentifier) {
			super(client, handler, listRequestIdentifier);
			this.context = context;
		}
		
		@Override
		void reschedule(ClientContext context) {
			context.ticker.queueTimedJob(this, 100);
		}
		
		@Override
		public void run() {
		    try {
		        context.jobRunner.queue(this, NativeThread.HIGH_PRIORITY-1);
		    } catch (PersistenceDisabledException e) {
		        outputHandler.queue(new EndListPersistentRequestsMessage(listRequestIdentifier));
		    }
		}
		
	}
	
	@Override
	public void run(final FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		
		PersistentRequestClient rebootClient = handler.getRebootClient();

		TransientListJob job = new TransientListJob(rebootClient, handler.outputHandler, node.clientCore.clientContext, identifier) {

			@Override
			void complete(ClientContext context) {
				
				if(handler.getRebootClient().watchGlobal) {
					PersistentRequestClient globalRebootClient = handler.server.globalRebootClient;

					TransientListJob job = new TransientListJob(globalRebootClient, outputHandler, context, listRequestIdentifier) {

						@Override
						void complete(ClientContext context) {
							finishComplete(context);
						}
						
					};
					job.run();
				} else {
					finishComplete(context);
				}
				
			}

			private void finishComplete(ClientContext context) {
					try {
                        context.jobRunner.queue(new PersistentJob() {

                        	@Override
                        	public boolean run(ClientContext context) {
                        		PersistentRequestClient foreverClient = handler.getForeverClient();
                        		PersistentListJob job = new PersistentListJob(foreverClient, outputHandler, context, listRequestIdentifier) {

                        			@Override
                        			void complete(ClientContext context) {
                        				if(handler.getRebootClient().watchGlobal) {
                        					PersistentRequestClient globalForeverClient = handler.server.globalForeverClient;
                        					PersistentListJob job = new PersistentListJob(globalForeverClient, outputHandler, context, listRequestIdentifier) {

                        						@Override
                        						void complete(
                        								ClientContext context) {
                        							finishFinal();
                        						}
                        						
                        					};
                        					job.run(context);
                        				} else {
                        					finishFinal();
                        				}
                        			}

                        			private void finishFinal() {
                        				outputHandler.queue(new EndListPersistentRequestsMessage(listRequestIdentifier));
                        			}
                        			
                        		};
                        		job.run(context);
                        		return false;
                        	}
                        }, NativeThread.HIGH_PRIORITY-1);
                    } catch (PersistenceDisabledException e) {
                        handler.send(new EndListPersistentRequestsMessage(listRequestIdentifier));
                    }
			}
			
		};
		job.run();
	}

}
