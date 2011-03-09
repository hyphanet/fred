package freenet.node;

import freenet.io.comm.AsyncMessageCallback;

/** Waits for multiple asynchronous message sends, then calls finish(). */
public abstract class MultiMessageCallback {
	
	private int waiting;
	
	private boolean armed;
	
	private boolean someFailed;
	
	abstract void finish(boolean success);

	public AsyncMessageCallback make() {
		synchronized(this) {
			AsyncMessageCallback cb = new AsyncMessageCallback() {

				private boolean finished;
				
				public void sent() {
					// Ignore
				}

				public void acknowledged() {
					complete(true);
				}

				public void disconnected() {
					complete(false);
				}

				public void fatalError() {
					complete(false);
				}
				
				private void complete(boolean success) {
					synchronized(MultiMessageCallback.this) {
						if(finished) return;
						if(!success) someFailed = true;
						finished = true;
						waiting--;
						if(!finished()) return;
						if(someFailed) success = false;
					}
					finish(success);
				}
				
			};
			waiting++;
			return cb;
		}
	}

	public void arm() {
		boolean success;
		synchronized(this) {
			armed = true;
			if(!finished()) return;
			success = !someFailed;
		}
		finish(success);
	}
	
	protected final synchronized boolean finished() {
		return armed && waiting == 0;
	}

}
