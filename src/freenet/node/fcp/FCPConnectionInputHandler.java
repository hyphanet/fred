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
			String messageType = lis.readLine(64, 64);
			if(messageType == null) {
				is.close();
				return;
			}
			if(messageType.equals("")) continue;
			fs = new SimpleFieldSet(lis, 4096, 128, false, false);
			FCPMessage msg;
			try {
				msg = FCPMessage.create(messageType, fs);
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
			if(msg instanceof DataCarryingMessage) {
				((DataCarryingMessage)msg).readFrom(lis, handler.bf);
			}
			if((!firstMessage) && msg instanceof ClientHelloMessage) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.NO_LATE_CLIENT_HELLOS, false, null, null);
				handler.outputHandler.queue(err);
				continue;
			}
			try {
				msg.run(handler, handler.node);
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
