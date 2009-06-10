package freenet.client.connection;

import freenet.client.update.IUpdateManager;

public interface IConnectionManager {
	public static final String	path	= "/pushdata/";

	public void openConnection();

	public void closeConnection();
}
