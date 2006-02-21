package freenet.node.fcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.WeakHashMap;

import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.node.Node;
import freenet.support.Logger;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable {

	final ServerSocket sock;
	final Node node;
	final int port;
	final WeakHashMap clientsByName;
	
	public FCPServer(int port, Node node) throws IOException {
		this.port = port;
		this.sock = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"));
		this.node = node;
		clientsByName = new WeakHashMap();
		Thread t = new Thread(this, "FCP server");
		t.setDaemon(true);
		t.start();
	}
	
	public void run() {
		while(true) {
			try {
				realRun();
			} catch (IOException e) {
				Logger.minor(this, "Caught "+e, e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
	}

	private void realRun() throws IOException {
		// Accept a connection
		Socket s = sock.accept();
		FCPConnectionHandler handler = new FCPConnectionHandler(s, node, this);
	}

	static class FCPPortNumberCallback implements IntCallback {

		final Node node;
		
		FCPPortNumberCallback(Node node) {
			this.node = node;
		}
		
		public int get() {
			return node.getFCPServer().port;
		}

		public void set(int val) throws InvalidConfigValueException {
			if(val != get()) {
				throw new InvalidConfigValueException("Cannot change FCP port number on the fly");
			}
		}
		
	}
	
	public static FCPServer maybeCreate(Node node, Config config) throws IOException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		// FIXME check enabled etc
		fcpConfig.register("port", 9481 /* anagram of 1984, and 1000 up from old number */,
				2, true, "FCP port number", "FCP port number", new FCPPortNumberCallback(node));
		FCPServer fcp = new FCPServer(fcpConfig.getInt("port"), node);
		node.setFCPServer(fcp);
		fcpConfig.finishedInitialization();
		return fcp;
	}

	public synchronized FCPClient registerClient(String name, FCPConnectionHandler handler) {
		FCPClient oldClient = (FCPClient) clientsByName.get(name);
		if(oldClient == null) {
			// Create new client
			FCPClient client = new FCPClient(name, handler);
			clientsByName.put(name, client);
			return client;
		} else {
			FCPConnectionHandler oldConn = oldClient.getConnection();
			// Have existing client
			if(oldConn == null) {
				// Easy
				oldClient.setConnection(handler);
			} else {
				// Kill old connection
				oldConn.outputHandler.queue(new CloseConnectionDuplicateClientNameMessage());
				oldConn.close();
				oldClient.setConnection(handler);
			}
			return oldClient;
		}
	}

}
