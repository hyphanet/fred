package freenet.node;

public class NullSendableRequestItem implements SendableRequestItem {

	public static final SendableRequestItem nullItem = new NullSendableRequestItem();

	public void dump() {
		// Do nothing, we will be GC'ed.
	}

}
