/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.fcp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;
import freenet.support.io.LineReadingInputStream;
import freenet.support.io.TooLongException;

public class FCPConnectionInputHandler implements Runnable {
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	final FCPConnectionHandler handler;

	FCPConnectionInputHandler(FCPConnectionHandler handler) {
		this.handler = handler;
	}

	void start() {
		if (handler.sock == null)
			return;
		handler.server.node.executor.execute(this, "FCP input handler for "+handler.sock.getRemoteSocketAddress());
	}

	@Override
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
		try {
			realRun();
		} catch (TooLongException e) {
			Logger.normal(this, "Caught "+e.getMessage(), e);
		} catch (IOException e) {
			if(logMINOR)
				Logger.minor(this, "Caught "+e, e);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			t.printStackTrace();
		}
		handler.close();
		handler.closedInput();
	}

	public void realRun() throws IOException {
		InputStream is = new BufferedInputStream(handler.sock.getInputStream(), 4096);
		LineReadingInputStream lis = new LineReadingInputStream(is);

		boolean firstMessage = true;

		while(true) {
			SimpleFieldSet fs;
			if(WrapperManager.hasShutdownHookBeenTriggered()) {
				FCPMessage msg = new ProtocolErrorMessage(ProtocolErrorMessage.SHUTTING_DOWN,true,"The node is shutting down","Node",false);
				handler.send(msg);
				Closer.close(is);
				return;
			}
			// Read a message
			String messageType = lis.readLine(128, 128, true);
			if(messageType == null) {
				Closer.close(is);
				return;
			}
			if(messageType.equals(""))
				continue;
			fs = new SimpleFieldSet(lis, 4096, 128, true, true, true);

			// check for valid endmarker
			if (!firstMessage && fs.getEndMarker() != null && (!fs.getEndMarker().startsWith("End")) && (!"Data".equals(fs.getEndMarker()))) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.MESSAGE_PARSE_ERROR, false, "Invalid end marker: "+fs.getEndMarker(), fs.get("Identifer"), fs.getBoolean("Global", false));
				handler.send(err);
				continue;
			}

			FCPMessage msg;
			try {
				if(logDEBUG)
					Logger.debug(this, "Incoming FCP message:\n"+messageType+'\n'+fs.toString());
				msg = FCPMessage.create(messageType, fs, handler.bf, handler.server.core.persistentTempBucketFactory);
				if(msg == null) continue;
			} catch (MessageInvalidException e) {
				if(firstMessage) {
					FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.CLIENT_HELLO_MUST_BE_FIRST_MESSAGE, true, null, null, false);
					handler.send(err);
					handler.close();
					Closer.close(is);
					return;
				} else {
					FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
					handler.send(err);
				}
				continue;
			}
			if(firstMessage && !(msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.CLIENT_HELLO_MUST_BE_FIRST_MESSAGE, true, null, null, false);
				handler.send(err);
				handler.close();
				Closer.close(is);
				return;
			}
			if(msg instanceof BaseDataCarryingMessage) {
				// FIXME tidy up - coalesce with above and below try { } catch (MIE) {}'s?
				try {
					((BaseDataCarryingMessage)msg).readFrom(lis, handler.bf, handler.server);
				} catch (MessageInvalidException e) {
					FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
					handler.send(err);
					continue;
				}
			}
			if((!firstMessage) && (msg instanceof ClientHelloMessage)) {
				FCPMessage err = new ProtocolErrorMessage(ProtocolErrorMessage.NO_LATE_CLIENT_HELLOS, false, null, null, false);
				handler.send(err);
				continue;
			}
			try {
				if(logDEBUG)
					Logger.debug(this, "Parsed message: "+msg+" for "+handler);
				msg.run(handler, handler.server.node);
			} catch (MessageInvalidException e) {
				FCPMessage err = new ProtocolErrorMessage(e.protocolCode, false, e.getMessage(), e.ident, e.global);
				handler.send(err);
				continue;
			}
			firstMessage = false;
			if(handler.isClosed()) {
				Closer.close(is);
				return;
			}
		}
	}

}
