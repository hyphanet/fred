/*
  FCPConnectionInputHandler.java / Freenet
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
import java.io.InputStream;

import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.io.LineReadingInputStream;

public class FCPConnectionInputHandler implements Runnable {

	final FCPConnectionHandler handler;
	
	FCPConnectionInputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
	}

	void start() {
		Thread t = new Thread(this, "FCP input handler for "+handler.sock.getRemoteSocketAddress());
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
			t.printStackTrace();
		}
		handler.close();
		handler.closedInput();
	}
	
	public void realRun() throws IOException {
		InputStream is = handler.sock.getInputStream();
		LineReadingInputStream lis = new LineReadingInputStream(is);
		
		boolean firstMessage = true;
		
		while(true) {
			SimpleFieldSet fs;
			// Read a message
			String messageType = lis.readLine(128, 128, true);
			if(messageType == null) {
				is.close();
				return;
			}
			if(messageType.equals("")) continue;
			fs = new SimpleFieldSet(lis, 4096, 128, true, true);
			FCPMessage msg;
			try {
				msg = FCPMessage.create(messageType, fs, handler.bf, handler.server.core.persistentTempBucketFactory);
				if(msg == null) continue;
			} catch (MessageInvalidException e) {
				FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident);
				handler.outputHandler.queue(err);
				continue;
			}
			if(firstMessage && !(msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.CLIENT_HELLO_MUST_BE_FIRST_MESSAGE, true, null, null);
				handler.outputHandler.queue(err);
				handler.close();
				continue;
			}
			if(msg instanceof BaseDataCarryingMessage) {
				// FIXME tidy up - coalesce with above and below try { } catch (MIE) {}'s?
				try {
					((BaseDataCarryingMessage)msg).readFrom(lis, handler.bf, handler.server);
				} catch (MessageInvalidException e) {
					FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident);
					handler.outputHandler.queue(err);
					continue;
				}
			}
			if((!firstMessage) && (msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.NO_LATE_CLIENT_HELLOS, false, null, null);
				handler.outputHandler.queue(err);
				continue;
			}
			try {
				msg.run(handler, handler.server.node);
			} catch (MessageInvalidException e) {
				FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident);
				handler.outputHandler.queue(err);
				continue;
			}
			firstMessage = false;
			if(handler.isClosed()) return;
		}
	}
}
