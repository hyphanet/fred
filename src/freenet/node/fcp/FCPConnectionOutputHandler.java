package freenet.node.fcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;

import freenet.support.Logger;

public class FCPConnectionOutputHandler implements Runnable {

	final FCPConnectionHandler handler;
	final LinkedList outQueue;
	
	public FCPConnectionOutputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
		this.outQueue = new LinkedList();
	}

	void start() {
		Thread t = new Thread(this, "FCP output handler for "+handler.sock.getRemoteSocketAddress()+":"+handler.sock.getPort());
		t.setDaemon(true);
		t.start();
	}
	
	public void run() {
		try {
			realRun();
		} catch (IOException e) {
			Logger.minor(this, "Caught "+e, e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
		}
		handler.close();
		handler.closedOutput();
	}

	private void realRun() throws IOException {
		OutputStream os = handler.sock.getOutputStream();
		while(true) {
			FCPMessage msg;
			synchronized(outQueue) {
				while(true) {
					if(outQueue.isEmpty()) {
						if(handler.isClosed()) return;
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
			if(handler.isClosed()) return;
		}
	}

	public void queue(FCPMessage msg) {
		if(msg == null) throw new NullPointerException();
		synchronized(outQueue) {
			outQueue.add(msg);
			outQueue.notifyAll();
		}
	}

}
