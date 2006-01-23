package freenet.node.fcp;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;

import freenet.client.FetcherContext;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InserterContext;
import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.Logger;

public class FCPConnectionHandler {

	final Socket sock;
	final FCPConnectionInputHandler inputHandler;
	final FCPConnectionOutputHandler outputHandler;
	final Node node;
	private boolean isClosed;
	private boolean inputClosed;
	private boolean outputClosed;
	private String clientName;
	final BucketFactory bf;
	final HashMap requestsByIdentifier;
	final FetcherContext defaultFetchContext;
	public InserterContext defaultInsertContext;
	
	public FCPConnectionHandler(Socket s, Node node) {
		this.sock = s;
		this.node = node;
		this.inputHandler = new FCPConnectionInputHandler(this);
		this.outputHandler = new FCPConnectionOutputHandler(this);
		isClosed = false;
		this.bf = node.tempBucketFactory;
		requestsByIdentifier = new HashMap();
		HighLevelSimpleClient client = node.makeClient((short)0,(short)0);
		defaultFetchContext = client.getFetcherContext();
		defaultInsertContext = client.getInserterContext();
		inputHandler.start();
	}
	
	public void close() {
		ClientRequest[] requests;
		synchronized(this) {
			isClosed = true;
			requests = new ClientRequest[requestsByIdentifier.size()];
			requests = (ClientRequest[]) requestsByIdentifier.values().toArray(requests);
		}
		for(int i=0;i<requests.length;i++)
			requests[i].cancel();
	}
	
	public boolean isClosed() {
		return isClosed;
	}
	
	public void closedInput() {
		try {
			sock.shutdownInput();
		} catch (IOException e) {
			// Ignore
		}
		synchronized(this) {
			inputClosed = true;
			if(!outputClosed) return;
		}
		try {
			sock.close();
		} catch (IOException e) {
			// Ignore
		}
	}
	
	public void closedOutput() {
		try {
			sock.shutdownOutput();
		} catch (IOException e) {
			// Ignore
		}
		synchronized(this) {
			outputClosed = true;
			if(!inputClosed) return;
		}
		try {
			sock.close();
		} catch (IOException e) {
			// Ignore
		}
	}

	public void setClientName(String name) {
		this.clientName = name;
	}
	
	public String getClientName() {
		return clientName;
	}

	public void startClientGet(ClientGetMessage message) {
		String id = message.identifier;
		if(requestsByIdentifier.containsKey(id)) {
			Logger.normal(this, "Identifier collision on "+this);
			FCPMessage msg = new IdentifierCollisionMessage(id);
			outputHandler.queue(msg);
			return;
		}
		synchronized(this) {
			if(isClosed) return;
			ClientGet cg = new ClientGet(this, message);
		}
	}

	public void startClientPut(ClientPutMessage message) {
		String id = message.identifier;
		if(requestsByIdentifier.containsKey(id)) {
			Logger.normal(this, "Identifier collision on "+this);
			FCPMessage msg = new IdentifierCollisionMessage(id);
			outputHandler.queue(msg);
			return;
		}
		synchronized(this) {
			if(isClosed) return;
			ClientPut cg = new ClientPut(this, message);
		}
		
		// TODO Auto-generated method stub
		
	}
}
