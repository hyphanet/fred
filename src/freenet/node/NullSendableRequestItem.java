package freenet.node;

public class NullSendableRequestItem implements SendableRequestItem, SendableRequestItemKey {

	public static final NullSendableRequestItem nullItem = new NullSendableRequestItem();

	@Override
	public void dump() {
		// Do nothing, we will be GC'ed.
	}

	@Override
	public SendableRequestItemKey getKey() {
		return this;
	}

}
