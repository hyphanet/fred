package freenet.node;

import freenet.io.comm.AsyncMessageCallback;

/** Waits for multiple asynchronous message sends, then calls finish(). */
public abstract class MultiMessageCallback {
	
	private int waiting;
	private int waitingForSend;
	
	private boolean armed;
	
	private boolean someFailed;
	
	abstract void finish(boolean success);
	
	abstract void sent();

	public AsyncMessageCallback make() {
		synchronized(this) {
			AsyncMessageCallback cb = new AsyncMessageCallback() {

				private boolean finished;
				private boolean sent;
				
				@Override
				public void sent() {
					synchronized(MultiMessageCallback.this) {
						if(finished || sent || !armed) return;
						sent = true;
						waitingForSend--;
						if(waitingForSend > 0) return;
					}
					MultiMessageCallback.this.sent();
				}

				@Override
				public void acknowledged() {
					complete(true);
				}

				@Override
				public void disconnected() {
					complete(false);
				}

				@Override
				public void fatalError() {
					complete(false);
				}
				
				private void complete(boolean success) {
					boolean callSent = false;
					synchronized(MultiMessageCallback.this) {
						if(finished) return;
						if(!sent) {
							sent = true;
							waitingForSend--;
							if(waitingForSend == 0)
								callSent = true;
						}
						if(!success) someFailed = true;
						finished = true;
						waiting--;
						if(!finished()) return;
						if(someFailed) success = false;
					}
					if(callSent)
						MultiMessageCallback.this.sent();
					finish(success);
				}
				
			};
			waiting++;
			waitingForSend++;
			return cb;
		}
	}

	public void arm() {
		boolean success;
		boolean callSent = false;
		boolean complete = false;
		synchronized(this) {
			armed = true;
			complete = waiting == 0;
			if(waitingForSend == 0) callSent = true;
			success = !someFailed;
		}
		if(callSent) sent();
		if(complete) finish(success);
	}
	
	protected final synchronized boolean finished() {
		return armed && waiting == 0;
	}

}
