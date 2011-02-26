package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import freenet.io.NetworkInterface;
import freenet.support.Executor;
import freenet.support.FileLoggerHook;
import freenet.support.FileLoggerHook.IntervalParseException;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.Logger.LogLevel;
import freenet.support.io.LineReadingInputStream;

/** Testnet controller. This runs on one system and testnet nodes connect to it to get
 * an ID, and then to check connectivity and report status.
 * 
 * FILE STRUCTURE:
 * testnet/nodes/
 * 
 * Within this, 0-9a-f by last digit of ID.
 * Within that, 0-9a-f by second last digit of ID.
 * Within that, one folder per ID.
 * @author toad
 */
public class TestnetController implements Runnable {
	
	private long counter;
	final File baseDir;
	final File nodesDir;
	final NetworkInterface networkInterface;
	final PooledExecutor executor;
	final TrivialTicker ticker;
	final HashMap<Long, TestnetNode> connectedTestnetNodes = new HashMap<Long, TestnetNode>();
	
	static final int PORT = 19840;
	
	TestnetController() throws IOException {
		baseDir = new File("testnet");
		if(!(baseDir.mkdirs() || baseDir.exists()))
			throw new IllegalStateException("Unable to start up: cannot make "+baseDir);
		nodesDir = new File(baseDir, "nodes");
		if(!(nodesDir.mkdirs() || nodesDir.exists()))
			throw new IllegalStateException("Unable to start up: cannot make "+nodesDir);
		initCounter();
		executor = new PooledExecutor();
		ticker = new TrivialTicker(executor);
		networkInterface = NetworkInterface.create(PORT, "0.0.0.0", "0.0.0.0/0", executor, true);
	}
	
	public void start() {
		executor.start();
	}
	
	private void initCounter() {
		counter = -1;
		for(int i=0;i<15;i++) {
			String s = Integer.toHexString(i);
			File dir = new File(nodesDir, s);
			if(!(dir.mkdirs() || dir.exists()))
				throw new IllegalStateException("Unable to make dir "+dir);
			for(int j=0;j<15;j++) {
				String t = Integer.toHexString(j);
				File subdir = new File(dir, t);
				if(!(subdir.mkdirs() || subdir.exists()))
					throw new IllegalStateException("Unable to make dir "+subdir);
				File[] fileList = subdir.listFiles();
				for(File f : fileList) {
					String filename = f.getName();
					try {
						long c = Integer.parseInt(filename, 16);
						if(c > counter) counter = c;
					} catch (NumberFormatException e) {
						continue;
					}
				}
			}
		}
		counter++;
		if(counter == -1) counter++; // -1 not allowed
		Logger.error(this, "Next counter is: "+counter);
	}

	public static void main(String[] args) throws IOException, IntervalParseException {
		setupLogging();

		System.err.println("Testnet controller starting up");
		TestnetController controller = new TestnetController();
		controller.start();
		controller.run();
	}

	private static void setupLogging() throws IOException, IntervalParseException {
		File logDir = new File("testnet-coordinator-logs");
		logDir.mkdir();
		if(!logDir.exists()) throw new IOException("Unable to create logs dir");
		Logger.setupChain();
		FileLoggerHook hook;
		hook = 
			new FileLoggerHook(true, new File(logDir, "testnet-coordinator").getAbsolutePath(), 
					"d (c, t, p): m", "MMM dd, yyyy HH:mm:ss:SSS", "1HOUR", LogLevel.DEBUG /* filtered by chain */, false, true, 
					1024*1024*1024, 100*1000);
		hook.setMaxListBytes(100*1000);
		hook.setMaxBacklogNotBusy(1*1000);
		Logger.globalAddHook(hook);
		hook.start();
		File latest = new File(logDir, "testnet-coordinator-latest.log");
		System.err.println("Logging to "+logDir);
		System.err.println("You should watch this file: "+latest);
		System.err.println("tail --follow=name --retry "+latest);
		System.err.println();
	}

	public void run() {
		while(true) {
			Socket s = networkInterface.accept();
			if(s == null) continue;
			Runnable handler = new SocketHandler(s);
			executor.execute(handler);
		}
	}
	
	final String VERIFYLOG = "verifylog";

	public class SocketHandler implements Runnable {
		
		final Socket sock;
		
		public SocketHandler(Socket s) {
			sock = s;
		}

		public void run() {
			boolean movedOn = false;
			try {
				Logger.normal(this, "Incoming connection from "+sock.getInetAddress());
				InputStream is = sock.getInputStream();
				BufferedInputStream bis = new BufferedInputStream(is);
				LineReadingInputStream lris; // Not using BufferedReader as we may need to pull binary data.
				lris = new LineReadingInputStream(bis);
				OutputStream os = sock.getOutputStream();
				BufferedOutputStream bos = new BufferedOutputStream(os);
				OutputStreamWriter osw = new OutputStreamWriter(bos);
				while(true) {
					String line = lris.readLine(1024, 128, true);
					if(line == null) return;
					if(line.equals("GENERATE")) {
						long id;
						try {
							id = generateID();
						} catch (IOException e) {
							osw.write("FAILED:GENERATE\n");
							osw.flush();
							continue;
						}
						osw.write("GENERATEDID:"+id+"\n");
						osw.flush();
					} else if(line.startsWith("READY:")) {
						Logger.normal(this, "Connection waiting for commands: "+sock.getInetAddress());
						long id;
						try {
							id = Long.parseLong(line.substring("READY:".length()));
						} catch (NumberFormatException e) {
							osw.write("ErrorCannotParseNodeID");
							return;
						}
						TestnetNode connected = 
							new TestnetNode(sock, lris, os, osw, id);
						synchronized(connectedTestnetNodes) {
							if(connectedTestnetNodes.containsKey(id)) {
								Logger.error(this, "Two connections from peer "+sock.getInetAddress()+" for testnet node "+id);
								connectedTestnetNodes.get(id).disconnect();
							}
							connectedTestnetNodes.put(id, connected);
						}
						executor.execute(connected);
						movedOn = true;
						onConnectedTestnetNodesChanged();
						return;
					} else {
						// Do nothing. Read the next line.
					}
				}
			} catch (IOException e) {
				// Grrrr.
			} finally {
				if(!movedOn) {
					try {
						sock.close();
					} catch (IOException e) {
						// Ignore.
					}
				}
			}
		}

	}
	
	abstract class TestnetCommand {
		final TestnetCommandType type;
		
		private TestnetCommand(TestnetCommandType type) {
			this.type = type;
		}
		
		protected void writeCommand(Writer w) throws IOException {
			w.write(type.name()+"\n");
			w.flush();
		}
		
		/** @return True to disconnect. */
		public abstract boolean execute(LineReadingInputStream lris, OutputStream os, Writer w, TestnetNode client) throws IOException;
	}
	
	class QuitCommand extends TestnetCommand {
		QuitCommand() {
			super(TestnetCommandType.Close);
		}

		@Override
		public boolean execute(LineReadingInputStream lris, OutputStream os,
				Writer w, TestnetNode client) {
			return true;
		}
	}
	
	class PingCommand extends TestnetCommand {
		PingCommand() {
			super(TestnetCommandType.Ping);
		}
		
		@Override
		public boolean execute(LineReadingInputStream lris, OutputStream os,
				Writer w, TestnetNode client) throws IOException {
			writeCommand(w);
			Logger.normal(this, "Waiting for reply to ping");
			String response = lris.readLine(1024, 20, true);
			Logger.normal(this, "Received reply to ping: \""+response+"\"");
			if(response == null) {
				Logger.error(this, "Timed out waiting for ping response, disconnecting");
				return true;
			}
			if(!response.equals("Pong")) {
				Logger.error(this, "Bogus return from ping, disconnecting");
				return true;
			}
			client.queuePing();
			Logger.normal(this, "Ping ok from "+client.id);
			return false;
		}
	}
	
	class TestnetNode implements Runnable {
		
		public TestnetNode(Socket sock, LineReadingInputStream lris2,
				OutputStream os2, OutputStreamWriter osw, long id) {
			this.socket = sock;
			this.lris = lris2;
			this.os = os2;
			this.w = osw;
			this.id = id;
		}
		
		public void disconnect() {
			commandQueue.add(new QuitCommand());
		}

		final Socket socket;
		final LineReadingInputStream lris;
		final OutputStream os;
		final Writer w;
		
		final long id;
		
		final BlockingQueue<TestnetCommand> commandQueue =
			new LinkedBlockingQueue<TestnetCommand>();
		
		public void run() {
			queuePing();
			try {
				while(true) {
					TestnetCommand command;
					try {
						command = commandQueue.take();
						Logger.normal(this, "Sending command to "+id+" : "+command);
					} catch (InterruptedException e) {
						continue;
					}
					if(command == null) return;
					try {
						if(command.execute(lris, os, w, this)) return;
					} catch (IOException e) {
						return;
					}
				}
			} finally {
				boolean removed = false;
				synchronized(connectedTestnetNodes) {
					if(connectedTestnetNodes.get(id) == this) {
						removed = true;
						connectedTestnetNodes.remove(id);
					}
				}
				try {
					socket.close();
				} catch (IOException e) {
					// Ignore
				}
				if(removed)
					onConnectedTestnetNodesChanged();
			}
		}

		private void queuePing() {
			ticker.queueTimedJob(new Runnable() {

				public void run() {
					commandQueue.add(new PingCommand());
				}
				
			}, PING_PERIOD);
		}
		
		
	}

	// FIXME increase???
	static final long PING_PERIOD = 300*1000;
	
	public synchronized long generateID() throws IOException {
		if(counter == -1) counter++; // -1 not allowed
		long newID = counter++;
		File dir = getDir(newID);
		dir.mkdirs();
		if(!dir.exists())
			throw new IOException();
		return newID;
	}

	public void onConnectedTestnetNodesChanged() {
		synchronized(connectedTestnetNodes) {
			Logger.normal(this, "Connected testnet nodes: "+connectedTestnetNodes.size());
		}
	}

	/** Fetch and record things like the node's noderef.
	 * Rotate once the log gets huge. 
	 * @param testnetNodeID The testnet ID of the node.
	 * @param addr The address to connect to.
	 * @param port The testnet port number.
	 * @param testSocket A socket connected to the testnet port. If we need to do multiple
	 * commands which disconnect we'll need to establish it again. */
	public void fetchRegularStuff(long testnetNodeID, InetAddress addr, int port2,
			Socket testSocket) {
		
		// TODO Auto-generated method stub
		
	}

	public boolean logVerify(long testnetNodeID, int port, InetAddress addr, int inPort) {
		// First, find the directory for the node.
		File dir = getDir(testnetNodeID);
		if(!dir.exists()) {
			return false;
		}
		File f = new File(dir, VERIFYLOG);
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(f, true);
			BufferedOutputStream bfos = new BufferedOutputStream(fos);
			OutputStreamWriter osw = new OutputStreamWriter(bfos, "UTF-8");
			osw.write(""+port+":"+System.currentTimeMillis()+":"+addr.getHostAddress()+":"+inPort+"\n");
			osw.flush();
		} catch (IOException e) {
			Logger.error(this, "Failed to write verify log: "+e);
			e.printStackTrace();
		} finally {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					Logger.error(this, "Failed to log verify attempt: "+e);
					e.printStackTrace();
				}
			}
		}
		return true;
	}

	private File getDir(long newID) {
		String hex = Long.toHexString(newID);
		String lastDigit = ""+hex.charAt(hex.length()-1);
		String prevDigit;
		if(hex.length() > 1)
			prevDigit = ""+hex.charAt(hex.length()-2);
		else
			prevDigit = "0";
		File dirName = new File(nodesDir, lastDigit);
		dirName = new File(dirName, prevDigit);
		dirName = new File(dirName, hex);
		return dirName;
	}

}
