/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.l10n.L10n;
import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;

/**
 * Testnet handler.
 * This is a simple server used for debugging. It allows remote developers
 * to access logfiles stored on this node, and may in future give more options
 * such as triggering an auto-update.
 * 
 * NOTE THAT IF THIS IS ENABLED, YOU HAVE NO ANONYMITY! It may also be possible
 * to exploit this for denial of service, as it is not authenticated in any way.
 * 
 * Currently provides two simple commands:
 * LIST\n - list all currently available logfiles, with size etc.
 * GET <date> - get the log file containing the given date.
 * No headers are sent, so you can simply capture netcat's output.
 * The idea is that this should be as simple as possible...
 */
public class TestnetHandler implements Runnable {
	
	private final TestnetStatusUploader uploader;
	
	public TestnetHandler(Node node2, int testnetPort) {
		this.node = node2;
		this.testnetPort = testnetPort;
		Logger.error(this, "STARTING TESTNET SERVER!");
		Logger.error(this, "ANONYMITY MODE: OFF");
		System.err.println("STARTING TESTNET SERVER!");
		System.err.println("ANONYMITY MODE: OFF");
		System.err.println("You have no anonymity. Thank you for running a testnet node, this will help the developers to efficiently debug Freenet, by letting them (and anyone else who knows how!!) automatically fetch your log files.");
		System.err.println("We repeat: YOU HAVE NO ANONYMITY WHATSOEVER. DO NOT POST ANYTHING YOU DO NOT WANT TO BE ASSOCIATED WITH.");
		System.err.println("If you want a real Freenet node, with anonymity, turn off testnet mode.");
		System.err.println("Note, this node will not connect to non-testnet nodes, for security reasons. You can of course run a testnet node and a non-testnet node separately.");
		uploader = new TestnetStatusUploader(node, 180000);
	}

	public void start() {
		serverThread = new Thread(this, "Testnet handler thread");
		serverThread.setDaemon(true);
		serverThread.start();
		uploader.start();
		System.err.println("Started testnet handler on port "+testnetPort);
	}
	
	private final Node node;
	private Thread serverThread;
	private ServerSocket server;
	private int testnetPort;
	
	public void run() {
	    freenet.support.OSThread.logPID(this);
		while(true){
			// Set up server socket
			try {
				server = new ServerSocket(testnetPort);
				Logger.normal(this,"Starting testnet server on port"+testnetPort);
			} catch (IOException e) {
				Logger.error(this, "Could not bind to testnet port: "+testnetPort);
				node.exit(NodeInitException.EXIT_TESTNET_FAILED);
				return;
			}
			while(!server.isClosed()) {
				try {
					Socket s = server.accept();
					new TestnetSocketHandler(s).start();
				} catch (IOException e) {
					Logger.error(this, "Testnet failed to accept socket: "+e, e);
				}	
			}
			Logger.normal(this, "Testnet handler has been stopped : restarting");
		}
	}
	
	public void rebind(int port){
		synchronized(server) {
			try{
				if((!server.isClosed()) && server.isBound())
					server.close();
				this.testnetPort=port;
			}catch( IOException e){
				Logger.error(this, "Error while stopping the testnet handler.");
				node.exit(NodeInitException.EXIT_TESTNET_FAILED);
				return;
			}
		}
	}
	
	public int getPort(){
		return testnetPort;
	}
	
	public class TestnetSocketHandler implements Runnable {

		private Socket s;
		
		public TestnetSocketHandler(Socket s2) {
			this.s = s2;
		}

		void start() {
			node.executor.execute(this, "Testnet handler for "+s.getInetAddress()+" at "+System.currentTimeMillis());
		}
		
		public void run() {
		    freenet.support.OSThread.logPID(this);
			boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
			InputStream is = null;
			OutputStream os = null;
			try {
				is = s.getInputStream();
				os = s.getOutputStream();
				// Read command
				InputStreamReader isr = new InputStreamReader(is, "ISO-8859-1");
				BufferedReader br = new BufferedReader(isr);
				String command = br.readLine();
				if(command == null) return;
				if(logMINOR) Logger.minor(this, "Command: "+command);
				FileLoggerHook loggerHook;
				loggerHook = Node.logConfigHandler.getFileLoggerHook();
				if(loggerHook == null) {
					Logger.error(this, "Could not serve testnet command because no FileLoggerHook");
					OutputStreamWriter osw = new OutputStreamWriter(os);
					osw.write("ERROR: Could not serve testnet command because no FileLoggerHook");
					return;
				}
				if(command.equalsIgnoreCase("LIST")) {
					if(logMINOR) Logger.minor(this, "Listing available logs");
					OutputStreamWriter osw = new OutputStreamWriter(os, "ISO-8859-1");
					loggerHook.listAvailableLogs(osw);
					osw.close();
				} else if(command.startsWith("GET:")) {
					if(logMINOR) Logger.minor(this, "Sending log: "+command);
					String date = command.substring("GET:".length());
					DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ENGLISH);
					df.setTimeZone(TimeZone.getTimeZone("GMT"));
					Date d;
					try {
						d = df.parse(date);
					} catch (ParseException e) {
						if(logMINOR) Logger.minor(this, "Cannot parse: "+e+" for "+date);
						return;
					}
					loggerHook.sendLogByContainedDate(d.getTime(), os);
				} else if(command.equalsIgnoreCase("STATUS")) {
					if(logMINOR) Logger.minor(this, "Sending status");
					OutputStreamWriter osw = new OutputStreamWriter(os, "ISO-8859-1");
					osw.write(node.getStatus());
					osw.close();
				} else if(command.equalsIgnoreCase("PEERS")) {
					if(logMINOR) Logger.minor(this, "Sending references");
					OutputStreamWriter osw = new OutputStreamWriter(os, "ISO-8859-1");
					BufferedWriter bw = new BufferedWriter(osw);
					bw.write("My darknet ref:\n\n");
					SimpleFieldSet fs = node.exportDarknetPublicFieldSet();
					fs.writeTo(bw);
					if(node.isOpennetEnabled()) {
						bw.write("My opennet ref:\n\n");
						fs = node.exportOpennetPublicFieldSet();
						fs.writeTo(bw);
					}
					bw.write("\n\nMy peers:\n");
					node.peers.writePeers(bw);
					bw.close();
				}else {
					Logger.error(this, "Unknown testnet command: "+command);
				}
			} catch (IOException e) {
				Logger.normal(this, "Failure handling testnet connection: "+e);
			} finally {
				if(is != null)
					try {
						is.close();
					} catch (IOException e) {
						// Ignore
					}
				if(os != null)
					try {
						os.close();
					} catch (IOException e) {
						// Ignore
					}
			}
		}

	}

	private static class TestnetEnabledCallback implements BooleanCallback {

		final Node node;
		
		TestnetEnabledCallback(Node node) {
			this.node = node;
		}
		
		public boolean get() {
			return node.testnetEnabled;
		}

		public void set(boolean val) throws InvalidConfigValueException {
			if(node.testnetEnabled == val) return;
			throw new InvalidConfigValueException(L10n.getString("TestnetHandler.cannotEnableDisableOnTheFly"));
		}
		
	}

	
	static class TestnetPortNumberCallback implements IntCallback {
		Node node;
		
		TestnetPortNumberCallback(Node n){
			this.node = n;
		}
		
		public int get() {
			return node.testnetHandler.getPort();
		}
		
		public void set(int val) throws InvalidConfigValueException {
			if(val == get()) return;
			node.testnetHandler.rebind(val);
		}
	}	
	
	
	public static TestnetHandler maybeCreate(Node node, Config config) throws NodeInitException {
        SubConfig testnetConfig = new SubConfig("node.testnet", config);
        
        testnetConfig.register("enabled", false, 1, true /*Switch it to false if we want large-scale testing */, true, "TestnetHandler.enable", "TestnetHandler.enableLong", new TestnetEnabledCallback(node));
        
        boolean enabled = testnetConfig.getBoolean("enabled");

        if(enabled) {
        	// Get the testnet port

        	testnetConfig.register("port", node.getDarknetPortNumber()+1000, 2, true, false, "TestnetHandler.port", "TestnetHandler.portLong",
        			new TestnetPortNumberCallback(node));

        	int port = testnetConfig.getInt("port");
        	
        	testnetConfig.finishedInitialization();
        	return new TestnetHandler(node, port);
        } else {
        	testnetConfig.finishedInitialization();
        	return null;
        }
	}

}
