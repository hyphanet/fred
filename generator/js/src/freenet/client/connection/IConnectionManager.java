package freenet.client.connection;

public interface IConnectionManager {
	public static final String	dataPath			= "/pushdata/";

	public static final String	notificationPath	= "/pushnotifications/";

	public static final String	keepalivePath		= "/keepalive/";

	public static final String	failoverPath		= "/failover/";

	public static final String	leavingPath			= "/leaving/";

	public void openConnection();

	public void closeConnection();
}
