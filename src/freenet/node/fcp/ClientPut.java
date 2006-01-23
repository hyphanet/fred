package freenet.node.fcp;

import freenet.client.ClientMetadata;
import freenet.client.FileInserter;
import freenet.client.InsertBlock;
import freenet.client.InserterContext;
import freenet.client.InserterException;
import freenet.keys.FreenetURI;

public class ClientPut extends ClientRequest implements Runnable {

	final FreenetURI uri;
	final FileInserter inserter;
	final InserterContext ctx;
	final InsertBlock block;
	final FCPConnectionHandler handler;
	final String identifier;
	final boolean getCHKOnly;
	
	public ClientPut(FCPConnectionHandler handler, ClientPutMessage message) {
		this.handler = handler;
		this.identifier = message.identifier;
		this.getCHKOnly = message.getCHKOnly;
		ctx = new InserterContext(handler.defaultInsertContext);
		// Now go through the fields one at a time
		uri = message.uri;
		String mimeType = message.contentType;
		block = new InsertBlock(message.bucket, new ClientMetadata(mimeType), uri);
		inserter = new FileInserter(ctx);
		ctx.maxInsertRetries = message.maxRetries;
		Thread t = new Thread(this, "FCP inserter for "+uri+" ("+identifier+") on "+handler);
		t.setDaemon(true);
		t.start();
	}

	public void cancel() {
		ctx.cancel();
	}

	public void run() {
		try {
			FreenetURI uri = inserter.run(block, false, getCHKOnly, false, null);
			FCPMessage msg = new PutSuccessfulMessage(identifier, uri);
			handler.outputHandler.queue(msg);
		} catch (InserterException e) {
			FCPMessage msg = new PutFailedMessage(e, identifier);
			handler.outputHandler.queue(msg);
		}
	}

}
