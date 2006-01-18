package freenet.node.fcp;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest {

	/** Cancel */
	public abstract void cancel();

}
