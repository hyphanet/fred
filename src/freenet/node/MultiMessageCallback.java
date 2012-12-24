package freenet.node;

import freenet.io.comm.AsyncMessageCallback;

/** Waits for multiple asynchronous message sends, then calls finish(). */
public abstract class MultiMessageCallback {
	
	private int waiting;
	private int waitingForSend;
	
	private boolean armed;
	
	private boolean someFailed;
	
	/** This is called when all messages have been acked, or failed */
	abstract void finish(boolean success);
	
	/** This is called when all messages have been sent (but not acked) or failed to send */
	abstract void sent(boolean success);

	public AsyncMessageCallback make() {
		synchronized(this) {
			AsyncMessageCallback cb = new AsyncMessageCallback() {

				private boolean finished;
				private boolean sent;
				
				@Override
				public void sent() {
					boolean success;
					synchronized(MultiMessageCallback.this) {
						if(finished || sent || !armed) return;
						sent = true;
						waitingForSend--;
						if(waitingForSend > 0) return;
						success = !someFailed;
					}
					MultiMessageCallback.this.sent(success);
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
						MultiMessageCallback.this.sent(success);
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
		if(callSent) sent(success);
		if(complete) finish(success);
	}
	
	protected final synchronized boolean finished() {
		return armed && waiting == 0;
	}

}
