package freenet.io.comm;

/** AsyncMessageFilterCallback where the callbacks may do things that take significant time. */
public interface SlowAsyncMessageFilterCallback extends
		AsyncMessageFilterCallback {

	public int getPriority();
	
}
