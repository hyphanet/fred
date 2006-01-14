package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.support.FileLoggerHook;
import freenet.support.Logger;

public class SimpleToadletServer implements ToadletContainer, Runnable {

	public class ToadletElement {
		public ToadletElement(Toadlet t2, String urlPrefix) {
			t = t2;
			prefix = urlPrefix;
		}
		Toadlet t;
		String prefix;
	}

	final int port;
	private final ServerSocket sock;
	private final LinkedList toadlets;
	
	public SimpleToadletServer(int i) throws IOException {
		this.port = i;
		this.sock = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"));
		toadlets = new LinkedList();
		Thread t = new Thread(this, "SimpleToadletServer");
		t.setDaemon(true);
		t.start();
	}

	public void register(Toadlet t, String urlPrefix, boolean atFront) {
		ToadletElement te = new ToadletElement(t, urlPrefix);
		if(atFront) toadlets.addFirst(te);
		else toadlets.addLast(te);
		t.container = this;
	}

	public Toadlet findToadlet(URI uri) {
		Iterator i = toadlets.iterator();
		while(i.hasNext()) {
			ToadletElement te = (ToadletElement) i.next();
			
			if(uri.getPath().startsWith(te.prefix))
				return te.t;
		}
		return null;
	}
	
	public static void main(String[] args) throws IOException {
        File logDir = new File("logs-toadlettest");
        logDir.mkdir();
        FileLoggerHook logger = new FileLoggerHook(true, new File(logDir, "test-1111").getAbsolutePath(), 
        		"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", Logger.MINOR, false, true, 
        		1024*1024*1024 /* 1GB of old compressed logfiles */);
        logger.setInterval("5MINUTES");
        Logger.setupChain();
        Logger.globalSetThreshold(Logger.MINOR);
        Logger.globalAddHook(logger);
        logger.start();
		SimpleToadletServer server = new SimpleToadletServer(1111);
		server.register(new TrivialToadlet(null), "", true);
		System.out.println("Bound to port 1111.");
		while(true) {
			try {
				Thread.sleep(100000);
			} catch (InterruptedException e) {
				// Ignore
			}
		}
	}

	public void run() {
		while(true) {
			try {
				Socket conn = sock.accept();
				Logger.minor(this, "Accepted connection");
				SocketHandler sh = new SocketHandler(conn);
			} catch (IOException e) {
				Logger.minor(this, "Got IOException accepting conn: "+e, e);
				// Ignore
				continue;
			}
		}
	}
	
	public class SocketHandler implements Runnable {

		Socket sock;
		
		public SocketHandler(Socket conn) {
			this.sock = conn;
			Thread t = new Thread(this);
			t.setDaemon(true);
			t.start();
		}

		public void run() {
			Logger.minor(this, "Handling connection");
			ToadletContextImpl.handle(sock, SimpleToadletServer.this);
			Logger.minor(this, "Handled connection");
		}

	}

}
