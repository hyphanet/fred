/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import freenet.support.Logger;
import freenet.support.OOMHandler;

public class FCPConnectionOutputHandler implements Runnable {

	final FCPConnectionHandler handler;
	final LinkedList outQueue;
	
	public FCPConnectionOutputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
		this.outQueue = new LinkedList();
	}

	void start() {
		handler.server.node.executor.execute(this, "FCP output handler for "+handler.sock.getRemoteSocketAddress()+ ':' +handler.sock.getPort());
	}
	
	public void run() {
	    freenet.support.OSThread.logPID(this);
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
			FCPMessage msg;
			synchronized(outQueue) {
				while(true) {
					if(outQueue.isEmpty()) {
						if(handler.isClosed()) return;
						os.flush();
						try {
							outQueue.wait(10000);
						} catch (InterruptedException e) {
							// Ignore
						}
						continue;
					}
					msg = (FCPMessage) outQueue.removeFirst();
					break;
				}
			}
			msg.send(os);
			if(handler.isClosed()) {
				os.flush();
				os.close();
				return;
			}
		}
	}

	public void queue(FCPMessage msg) {
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Queueing "+msg, new Exception("debug"));
		if(msg == null) throw new NullPointerException();
		synchronized(outQueue) {
			outQueue.add(msg);
			outQueue.notifyAll();
		}
	}

}
