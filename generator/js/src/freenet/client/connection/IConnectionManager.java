package freenet.client.connection;

public interface IConnectionManager {
	public static final String	dataPath			= "/pushdata/";

	public static final String	notificationPath	= "/pushnotifications/";

	public void openConnection();

	public void closeConnection();
}
