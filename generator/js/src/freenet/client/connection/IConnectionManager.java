package freenet.client.connection;

/** This interface represents a class that manages a connection that can be opened and closed */
public interface IConnectionManager {
	/** Opens the connection */
	public void openConnection();

	/** Closes the connection */
	public void closeConnection();
}
