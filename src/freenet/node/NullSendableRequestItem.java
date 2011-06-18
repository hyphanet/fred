package freenet.node;

public class NullSendableRequestItem implements SendableRequestItem {

	public static final SendableRequestItem nullItem = new NullSendableRequestItem();

	@Override
	public void dump() {
		// Do nothing, we will be GC'ed.
	}

}
