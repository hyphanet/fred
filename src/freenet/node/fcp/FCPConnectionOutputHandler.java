/*
  FCPConnectionOutputHandler.java / Freenet
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

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
			if(Logger.shouldLog(Logger.MINOR, this))
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
