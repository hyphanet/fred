package freenet.node.fcp;

/**
 * An FCP client.
 * Identified by its Name which is sent on connection.
 */
public class FCPClient {

	public FCPClient(String name2, FCPConnectionHandler handler) {
		this.name = name2;
		this.currentConnection = handler;
	}
	
	/** The client's Name sent in the ClientHello message */
	final String name;
	/** The current connection handler, if any. */
	private FCPConnectionHandler currentConnection;
	
	public FCPConnectionHandler getConnection() {
		return currentConnection;
	}

	public void setConnection(FCPConnectionHandler handler) {
		this.currentConnection = handler;
	}
	
}
