package freenet.node;

/**
 * Something that wants to listen for nodeToNodeMessage's.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface NodeToNodeMessageListener {
	
	public void handleMessage(byte[] data, boolean fromDarknet, PeerNode source, int type);

}
