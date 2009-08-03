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

public class FCPConnectionOutputHandler implements Runnable {

	final FCPConnectionHandler handler;
	final LinkedList<FCPMessage> outQueue;
	
	public FCPConnectionOutputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
		this.outQueue = new LinkedList<FCPMessage>();
	}

	void start() {
		handler.server.node.executor.execute(this, "FCP output handler for "+handler.sock.getRemoteSocketAddress()+ ':' +handler.sock.getPort());
	}
	
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		try {
			realRun();
		} catch (IOException e) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Caught "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
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
						if(closed) break;
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
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Queueing "+msg, new Exception("debug"));
		if(msg == null) throw new NullPointerException();
		if(handler.isClosed()) {
			Logger.error(this, "Closed already: "+this+" queueing message "+msg);
			// FIXME throw something???
			return;
		}
		synchronized(outQueue) {
			outQueue.add(msg);
			outQueue.notifyAll();
		}
	}

	public void onClosed() {
		synchronized(outQueue) {
			outQueue.notifyAll();
		}
		// Give a chance to the output handler to flush
		// its queue before the socket is closed
		// @see #2019 - nextgens
		while(!outQueue.isEmpty()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {}
		}
	}

	public boolean objectCanNew(ObjectContainer container) {
		throw new UnsupportedOperationException("FCPConnectionOutputHandler storage in database not supported");
	}
	
}
