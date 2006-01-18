package freenet.node.fcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import freenet.node.Node;
import freenet.support.Logger;

/**
 * FCP server process.
 */
public class FCPServer implements Runnable {

	final ServerSocket sock;
	final Node node;
	
	public FCPServer(int port, Node node) throws IOException {
		this.sock = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"));
		this.node = node;
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
		FCPConnectionHandler handler = new FCPConnectionHandler(s, node);
	}

}
