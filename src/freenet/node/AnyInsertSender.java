package freenet.node;

public interface AnyInsertSender {

	public abstract int getStatus();

	public abstract short getHTL();

	/**
	 * @return The current status as a string
	 */
	public abstract String getStatusString();

	public abstract boolean sentRequest();

}