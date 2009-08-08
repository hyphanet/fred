package freenet.clients.http;

/** This listener interface can be used to register to an FProxyFetchInProgress to get notified when the fetch's status is changed */
public interface FProxyFetchListener {
	/** Will be called when the fetch's status is changed */
	public void onEvent();

}
