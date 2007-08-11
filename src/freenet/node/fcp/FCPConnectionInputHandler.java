/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.IOException;
import java.io.InputStream;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.io.LineReadingInputStream;

public class FCPConnectionInputHandler implements Runnable {

	final FCPConnectionHandler handler;
	
	FCPConnectionInputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
	}

	void start() {
		handler.server.node.executor.execute(this, "FCP input handler for "+handler.sock.getRemoteSocketAddress());
	}
	
	public void run() {
		try {
			realRun();
		} catch (IOException e) {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Caught "+e, e);
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
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
			if(WrapperManager.hasShutdownHookBeenTriggered()) {
				FCPMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.SHUTTING_DOWN,true,"The node is shutting down","Node",false);
				handler.outputHandler.queue(msg);
				try {
					is.close();
				} catch (IOException e) {
					// Don't care
				}
				return;
			}
			// Read a message
			String messageType = lis.readLine(128, 128, true);
			if(messageType == null) {
				is.close();
				return;
			}
			if(messageType.equals("")) continue;
			fs = new SimpleFieldSet(lis, 4096, 128, true, true, true, true);
			
			// check for valid endmarker
			if (fs.getEndMarker() != null && (!fs.getEndMarker().startsWith("End")) && (!"Data".equals(fs.getEndMarker()))) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, false, "Invalid end marker: "+fs.getEndMarker(), fs.get("Identifer"), fs.getBoolean("Global", false));
				handler.outputHandler.queue(err);
				continue;
			}
			
			FCPMessage msg;
			try {
				if(Logger.shouldLog(Logger.DEBUG, this))
					Logger.debug(this, "Incoming FCP message:\n"+messageType+'\n'+fs.toString());
				msg = FCPMessage.create(messageType, fs, handler.bf, handler.server.core.persistentTempBucketFactory);
				if(msg == null) continue;
			} catch (MessageInvalidException e) {
				FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
				handler.outputHandler.queue(err);
				continue;
			}
			if(firstMessage && !(msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.CLIENT_HELLO_MUST_BE_FIRST_MESSAGE, true, null, null, false);
				handler.outputHandler.queue(err);
				handler.close();
				continue;
			}
			if(msg instanceof BaseDataCarryingMessage) {
				// FIXME tidy up - coalesce with above and below try { } catch (MIE) {}'s?
				try {
					((BaseDataCarryingMessage)msg).readFrom(lis, handler.bf, handler.server);
				} catch (MessageInvalidException e) {
					FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
					handler.outputHandler.queue(err);
					continue;
				}
			}
			if((!firstMessage) && (msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.NO_LATE_CLIENT_HELLOS, false, null, null, false);
				handler.outputHandler.queue(err);
				continue;
			}
			try {
				if(Logger.shouldLog(Logger.DEBUG, this))
					Logger.debug(this, "Parsed message: "+msg+" for "+handler);
				msg.run(handler, handler.server.node);
			} catch (MessageInvalidException e) {
				FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
				handler.outputHandler.queue(err);
				continue;
			}
			firstMessage = false;
			if(handler.isClosed()) return;
		}
	}
}
