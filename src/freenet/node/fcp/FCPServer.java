package freenet.node.fcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.WeakHashMap;

import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
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
	final boolean enabled;
	final String bindto;
	final WeakHashMap clientsByName;
	
	public FCPServer(int port, Node node) throws IOException {
		this.port = port;
		this.enabled = true;
		this.bindto = new String("127.0.0.1");
		this.sock = new ServerSocket(port, 0, InetAddress.getByName(bindto));
		this.node = node;
		clientsByName = new WeakHashMap();
		Thread t = new Thread(this, "FCP server");
		t.setDaemon(true);
		t.start();
	}
	
	public FCPServer(String ipToBindTo, int port, Node node) throws IOException {
		this.bindto = new String(ipToBindTo);
		this.port = port;
		this.enabled = true;
		this.sock = new ServerSocket(port, 0, InetAddress.getByName(bindto));
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
	
	static class FCPEnabledCallback implements BooleanCallback{

		final Node node;
		
		FCPEnabledCallback(Node node) {
			this.node = node;
		}
		
		public boolean get() {
			return node.getFCPServer().enabled;
		}
//TODO: Allow it
		public void set(boolean val) throws InvalidConfigValueException {
			if(val != get()) {
				throw new InvalidConfigValueException("Cannot change the status of the FCP server on the fly");
			}
		}
	}

	static class FCPBindtoCallback implements StringCallback{

		final Node node;
		
		FCPBindtoCallback(Node node) {
			this.node = node;
		}
		
		public String get() {
			return node.getFCPServer().bindto;
		}

//TODO: Allow it
		public void set(String val) throws InvalidConfigValueException {
			if(val.equals(get())) {
				throw new InvalidConfigValueException("Cannot change the ip address the server is binded to on the fly");
			}
		}
	}

	
	public static FCPServer maybeCreate(Node node, Config config) throws IOException {
		SubConfig fcpConfig = new SubConfig("fcp", config);
		fcpConfig.register("enabled", true, 2, true, "Is FCP server enabled ?", "Is FCP server enabled ?", new FCPEnabledCallback(node));
		fcpConfig.register("port", 9481 /* anagram of 1984, and 1000 up from old number */,
				2, true, "FCP port number", "FCP port number", new FCPPortNumberCallback(node));
		fcpConfig.register("bindto", "127.0.0.1", 2, true, "Ip address to bind to", "Ip address to bind the FCP server to", new FCPBindtoCallback(node));
		
		FCPServer fcp;
		if(fcpConfig.getBoolean("enabled")){
			Logger.normal(node, "Starting FCP server on "+fcpConfig.getString("bindto")+":"+fcpConfig.getInt("port")+".");
			fcp = new FCPServer(fcpConfig.getString("bindto"), fcpConfig.getInt("port"), node);
			node.setFCPServer(fcp);	
		}else{
			Logger.normal(node, "Not starting FCP server as it's disabled");
			fcp = null;
		}
		fcpConfig.finishedInitialization();
		return fcp;
	}

	public FCPClient registerClient(String name, FCPConnectionHandler handler) {
		FCPClient oldClient;
		synchronized(this) {
			oldClient = (FCPClient) clientsByName.get(name);
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
					return oldClient;
				}
			}
		}
		oldClient.queuePendingMessagesOnConnectionRestart(handler.outputHandler);
		return oldClient;
	}

}
