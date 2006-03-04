package freenet.node.fcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest {

	/** Lost connection */
	public abstract void onLostConnection();
	
	/** Is the request persistent? False = we can drop the request if we lose the connection */
	public abstract boolean isPersistent();

	/** Completed request dropped off the end without being acknowledged */
	public abstract void dropped();

	/** Get identifier string for request */
	public abstract String getIdentifier();
	
	/** Send any pending messages for a persistent request e.g. after reconnecting */
	public abstract void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest);

	// Persistence
	
	static final short PERSIST_CONNECTION = 0;
	static final short PERSIST_REBOOT = 1;
	static final short PERSIST_FOREVER = 2;
	
	public static String persistenceTypeString(short type) {
		switch(type) {
		case PERSIST_CONNECTION:
			return "connection";
		case PERSIST_REBOOT:
			return "reboot";
		case PERSIST_FOREVER:
			return "forever";
		default:
			return Short.toString(type);
		}
	}

	public static short parsePersistence(String string) {
		if(string == null || string.equalsIgnoreCase("connection"))
			return PERSIST_CONNECTION;
		if(string.equalsIgnoreCase("reboot"))
			return PERSIST_REBOOT;
		if(string.equalsIgnoreCase("forever"))
			return PERSIST_FOREVER;
		return Short.parseShort(string);
	}

	/**
	 * Write a persistent request to disk.
	 * @throws IOException 
	 */
	public abstract void write(BufferedWriter w) throws IOException;

	public static ClientRequest readAndRegister(BufferedReader br, FCPServer server) throws IOException {
		SimpleFieldSet fs = new SimpleFieldSet(br, true);
		String clientName = fs.get("ClientName");
		boolean isGlobal = Fields.stringToBool(fs.get("Global"), false);
		FCPClient client;
		if(!isGlobal)
			client = server.registerClient(clientName, server.node, null);
		else
			client = server.globalClient;
		try {
			String type = fs.get("Type");
			if(type.equals("GET")) {
				ClientGet cg = new ClientGet(fs, client);
				client.register(cg);
				return cg;
			} else if(type.equals("PUT")) {
				ClientPut cp = new ClientPut(fs, client);
				client.register(cp);
				return cp;
			} else {
				Logger.error(ClientRequest.class, "Unrecognized type: "+type);
				return null;
			}
		} catch (Throwable t) {
			Logger.error(ClientRequest.class, "Failed to parse: "+t, t);
			return null;
		}
	}

	public abstract boolean hasFinished();
	
	public abstract boolean isPersistentForever();

}
