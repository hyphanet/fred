package freenet.client.update;

/** This interface can be used to register listeners to the DefaultUpdateManager */
public interface IUpdateListener {
	/** An element has been updated */
	public void onUpdate();
}
