package freenet.node;

import freenet.io.comm.AsyncMessageCallback;
import freenet.support.Logger;

/** Waits for multiple asynchronous message sends, then calls finish(). */
public abstract class MultiMessageCallback {
    
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(MultiMessageCallback.class);
    }
	
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
                    if(logMINOR) Logger.minor(this, "sent() on "+this+" for "+
                            MultiMessageCallback.this);
					boolean success;
					synchronized(MultiMessageCallback.this) {
						if(finished || sent) return;
						sent = true;
						waitingForSend--;
						if(waitingForSend > 0) return;
                        if(!armed) return;
						success = !someFailed;
					}
					if(logMINOR) Logger.minor(this, "sent() calling sent() for "+this+" for "+
					        MultiMessageCallback.this);
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
				    if(logMINOR) Logger.minor(this, "Complete("+success+") on "+this+" for "+
				            MultiMessageCallback.this);
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
					if(callSent) {
					    if(logMINOR) Logger.minor(this, "complete() calling sent() for "+this+
					            " for "+MultiMessageCallback.this);
						MultiMessageCallback.this.sent(success);
					}
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
		if(callSent) {
            if(logMINOR) Logger.minor(this, "arm() calling sent() for "+this+
                    " for "+MultiMessageCallback.this);
		    sent(success);
		}
		if(complete) finish(success);
	}
	
	protected final synchronized boolean finished() {
		return armed && waiting == 0;
	}

}
