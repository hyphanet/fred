/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

import com.db4o.ObjectContainer;
import freenet.support.LogThresholdCallback;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LogLevel;

public class FCPConnectionOutputHandler implements Runnable {

	final FCPConnectionHandler handler;
	final Deque<FCPMessage> outQueue;
	// Synced on outQueue
	private boolean closedOutputQueue;

        private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                                logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}
	
	public FCPConnectionOutputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
		this.outQueue = new ArrayDeque<FCPMessage>();
	}

	void start() {
		if (handler.sock == null)
			return;
		handler.server.node.executor.execute(this, "FCP output handler for "+handler.sock.getRemoteSocketAddress()+ ':' +handler.sock.getPort());
	}
	
	@Override
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		try {
			realRun();
		} catch (IOException e) {
			if(logMINOR)
				Logger.minor(this, "Caught "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
		} finally {
			// Set the closed flag so that onClosed(), both on this thread and the input thread, doesn't wait forever.
			// This happens in realRun() on a healthy exit, but we must do it here too to handle an exceptional exit.
			// I.e. the other side closed the connection, and we threw an IOException.
			synchronized(outQueue) {
				closedOutputQueue = true;
			}
		}
		handler.close();
		handler.closedOutput();
	}
 
	private void realRun() throws IOException {
		OutputStream os = new BufferedOutputStream(handler.sock.getOutputStream(), 4096);
		while(true) {
			boolean closed;
			FCPMessage msg = null;
			boolean flushed = false;
			while(true) {
				closed = handler.isClosed();
				boolean shouldFlush = false;
				synchronized(outQueue) {
					if(outQueue.isEmpty()) {
						if(closed) {
							closedOutputQueue = true;
							outQueue.notifyAll();
							break;
						}
						if(!flushed)
							shouldFlush = true;
						else {
							try {
								outQueue.wait();
							} catch (InterruptedException e) {
								// Ignore
							}
							continue;
						}
					} else {
						msg = outQueue.removeFirst();
					}
				}
				if(shouldFlush) {
					if(logMINOR) Logger.minor(this, "Flushing");
					os.flush();
					flushed = true;
					continue;
				} else {
					break;
				}
			}
			if(msg == null) {
				if(closed) {
					os.flush();
					os.close();
					return;
				}
			} else {
				if(logMINOR) Logger.minor(this, "Sending "+msg);
				msg.send(os);
				flushed = false;
			}
		}
	}

	public void queue(FCPMessage msg) {
		if(logDEBUG)
			Logger.debug(this, "Queueing "+msg, new Exception("debug"));
		if(msg == null) throw new NullPointerException();
		boolean neverDropAMessage = handler.server.neverDropAMessage();
		int MAX_QUEUE_LENGTH = handler.server.maxMessageQueueLength();
		synchronized(outQueue) {
			if(closedOutputQueue) {
				Logger.error(this, "Closed already: "+this+" queueing message "+msg);
				// FIXME throw something???
				return;
			}
			if(outQueue.size() >= MAX_QUEUE_LENGTH) {
				if(neverDropAMessage) {
					Logger.error(this, "FCP message queue length is "+outQueue.size()+" for "+handler+" - not dropping message as configured...");
				} else {
					Logger.error(this, "Dropping FCP message to "+handler+" : "+outQueue.size()+" messages queued - maybe client died?", new Exception("debug"));
					return;
				}
			}
			outQueue.add(msg);
			outQueue.notifyAll();
		}
	}

	public void onClosed() {
		synchronized(outQueue) {
			outQueue.notifyAll();
			// Give a chance to the output handler to flush
			// its queue before the socket is closed
			// @see #2019 - nextgens
			while(!outQueue.isEmpty()) {
				if(closedOutputQueue) return;
				try {
					outQueue.wait();
				} catch (InterruptedException e) {
					continue;
				}
			}
		}
	}

	public boolean objectCanNew(ObjectContainer container) {
		throw new UnsupportedOperationException("FCPConnectionOutputHandler storage in database not supported");
	}

	public boolean isQueueHalfFull() {
		int MAX_QUEUE_LENGTH = handler.server.maxMessageQueueLength();
		synchronized(outQueue) {
			return outQueue.size() > MAX_QUEUE_LENGTH / 2;
		}
	}
	
}
