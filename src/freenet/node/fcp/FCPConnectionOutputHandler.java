/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.Logger.LoggerPriority;

public class FCPConnectionOutputHandler implements Runnable {

	final FCPConnectionHandler handler;
	final LinkedList<FCPMessage> outQueue;
	// Synced on outQueue
	private boolean closedOutputQueue;
	
	public FCPConnectionOutputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
		this.outQueue = new LinkedList<FCPMessage>();
	}

	void start() {
		if (handler.sock == null)
			return;
		handler.server.node.executor.execute(this, "FCP output handler for "+handler.sock.getRemoteSocketAddress()+ ':' +handler.sock.getPort());
	}
	
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		try {
			realRun();
		} catch (IOException e) {
			if(Logger.shouldLog(LoggerPriority.MINOR, this))
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
			while(true) {
				closed = handler.isClosed();
				synchronized(outQueue) {
					if(outQueue.isEmpty()) {
						if(closed) {
							closedOutputQueue = true;
							outQueue.notifyAll();
							break;
						}
						os.flush();
						try {
							outQueue.wait();
						} catch (InterruptedException e) {
							// Ignore
						}
						continue;
					}
					msg = outQueue.removeFirst();
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
				msg.send(os);
			}
		}
	}

	public void queue(FCPMessage msg) {
		if(Logger.shouldLog(LoggerPriority.DEBUG, this))
			Logger.debug(this, "Queueing "+msg, new Exception("debug"));
		if(msg == null) throw new NullPointerException();
		synchronized(outQueue) {
			if(closedOutputQueue) {
				Logger.error(this, "Closed already: "+this+" queueing message "+msg);
				// FIXME throw something???
				return;
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
	
}
