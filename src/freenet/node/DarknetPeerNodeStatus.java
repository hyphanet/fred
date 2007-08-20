package freenet.node;

public class DarknetPeerNodeStatus extends PeerNodeStatus {

	private final String name;

	private final boolean burstOnly;

	private final boolean listening;

	private final boolean disabled;

	private final String privateDarknetCommentNote;
	
	public DarknetPeerNodeStatus(DarknetPeerNode peerNode) {
		super(peerNode);
		this.name = peerNode.getName();
		this.burstOnly = peerNode.isBurstOnly();
		this.listening = peerNode.isListenOnly();
		this.disabled = peerNode.isDisabled();
		this.privateDarknetCommentNote = peerNode.getPrivateDarknetCommentNote();
	}
	
	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return the burstOnly
	 */
	public boolean isBurstOnly() {
		return burstOnly;
	}

	/**
	 * @return the disabled
	 */
	public boolean isDisabled() {
		return disabled;
	}

	/**
	 * @return the listening
	 */
	public boolean isListening() {
		return listening;
	}

	/**
	 * @return the privateDarknetCommentNote
	 */
	public String getPrivateDarknetCommentNote() {
		return privateDarknetCommentNote;
	}

	public String toString() {
		return name + ' ' + super.toString();
	}
}
