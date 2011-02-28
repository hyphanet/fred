/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.support.FileLoggerHook;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * Testnet StatusUploader.
 * This is a simple client for uploading status of the client to the
 * auditing server, which parses information and adds it to the database 
 * 
 * NOTE THAT IF THIS IS ENABLED, YOU HAVE NO ANONYMITY! It may also be possible
 * to exploit this for denial of service, as it is not authenticated in any way.
 * 
 * 
 */
public class TestnetStatusUploader {
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(TestnetStatusUploader.class);
	}

	public TestnetStatusUploader(Node node2, int updateInterval) {
		this.node = node2;
		this.updateInterval = updateInterval;
		Logger.error(this, "STARTING TESTNET STATUSUPLOADER!");
		Logger.error(this, "ANONYMITY MODE: OFF");
		System.err.println("STARTING TESTNET STATUSUPLOADER!");
		System.err.println("ANONYMITY MODE: OFF");
		System.err.println("You have no anonymity. Thank you for running a testnet node, this will help the developers to efficiently debug Freenet, by letting them (and anyone else who knows how!!) automatically fetch your log files.");
		System.err.println("We repeat: YOU HAVE NO ANONYMITY WHATSOEVER. DO NOT POST ANYTHING YOU DO NOT WANT TO BE ASSOCIATED WITH.");
		System.err.println("If you want a real Freenet node, with anonymity, turn off testnet mode.");
	}

	void start() {
		waitForConnectivity();
	}
	
	private final Node node;
	private final int updateInterval;
	
	static final String serverAddress = "amphibian.dyndns.org";
	static final int serverPort = TestnetController.PORT;
	
	/** Manages the persistent connection to the testnet controller. 
	 * The testnet controller can ask for various things such as the contents of logs.
	 * It usually returns a single line or a SimpleFieldSet. If data is returned the 
	 * length must be returned first. */
	class TestnetConnectionHandler implements Runnable {
		final Socket client;
		final BufferedReader br;
		final Writer w;
		final OutputStream os;
		public TestnetConnectionHandler(Socket client, BufferedReader br,
				Writer w, OutputStream os) {
			this.client = client;
			this.br = br;
			this.w = w;
			this.os = os;
		}
		public void run() {
			System.out.println("Talking to testnet coordinator");
			try {
				handleTestnetConnection();
			} finally {
				if(logMINOR) Logger.minor(this, "Connection to testnet handler closing");
				synchronized(TestnetStatusUploader.this) {
					if(connectionHandler == this) {
						connectionHandler = null;
					}
				}
				try {
					client.close();
				} catch (IOException e) {
					// Ignore
				}
				System.out.println("Disconnected from testnet coordinator");
				waitForConnectivity();
			}
		}
		private void handleTestnetConnection() {
			try {
				w.write("READY:"+node.testnetID+"\n");
				w.flush();
				System.out.println("Waiting for commands from testnet coordinator");
				while(true) {
					String command = br.readLine();
					if(command == null) return;
					try {
						if(handleCommandFromTestnetController(command, br, w, os)) return;
						w.flush();
						if(logMINOR) Logger.minor(this, "Handled command");
					} catch (IOException e) {
						return;
					} catch (Throwable t) {
						Logger.error(this, "Failed to handle testnet command \""+command+"\"", t);
						// Something wierd, maybe startup glitch???
						// Try again.
						w.write("ErrorOuter\n");
					}
				}
			} catch (IOException e) {
				return;
			}
		}
	}
	
	private boolean verifyingConnectivity;
	private TestnetConnectionHandler connectionHandler;
	
	public boolean verifyConnectivity() {
		System.out.println("Will connect to testnet coordinator");
		synchronized(this) {
			if(verifyingConnectivity) return true;
			if(connectionHandler != null) return true;
			verifyingConnectivity = true;
		}
		Socket client = null;
		
		boolean success = false;
		// Set up client socket
		try
		{
			
			System.out.println("Connecting to testnet coordinator");
			client = new Socket(serverAddress, serverPort);
			
			System.out.println("Connected to testnet coordinator");
			
			InputStream is = client.getInputStream();
			OutputStream os = client.getOutputStream();
			InputStreamReader isr = new InputStreamReader(new BufferedInputStream(is), "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			OutputStreamWriter osw = new OutputStreamWriter(new BufferedOutputStream(os));
			
			synchronized(this) {
				verifyingConnectivity = false;
				connectionHandler = new TestnetConnectionHandler(client, br, new BufferedWriter(osw), os);
				client = null;
				success = true;
				node.executor.execute(connectionHandler);
			}
//			// Verify connectivity.
//			osw.write("VERIFY:"+node.testnetID+":"+testnetPort+"\n");
//			osw.flush();
//			
//			String reply = br.readLine();
//			
//			if(reply == null) {
//				throw new IOException("Lost connection waiting for response");
//			}
//			
//			if(reply.equals("OK")) {
//				System.out.println("Successfully verified connectivity with testnet controller.");
//				return true;
//			}
//			System.err.println("Connectivity check failed: \""+reply+"\"");
//			return false;
			return true;
		} catch (IOException e){
			Logger.error(this, "Could not verify connectivity to the uploadhost: "+e);
			System.err.println("Could not verify connectivity to the uploadhost: "+e);
			return false;
		} finally {
			synchronized(this) {
				if(!success) {
					verifyingConnectivity = false;
					connectionHandler = null;
				}
			}
			try {
				if(client != null)
					client.close();
			} catch (IOException e) {
				// Ignore
			}
		}
		
	}
	
	/** 
	 * @param OutputStream YOU MUST FLUSH THE WRITER BEFORE USING THIS!!!
	 * @return True to close the connection.
	 * @throws IOException */
	public boolean handleCommandFromTestnetController(String command, BufferedReader br, Writer w, OutputStream os) throws IOException {
		Logger.normal(this, "Received command from testnet controller: \""+command+"\"");
		if(command.equals("Close")) {
			return true;
		} else if(command.equals("Ping")) {
			w.write("Pong\n");
		} else if(command.equals("GetMyReference")) {
			w.write("MyReference\n");
			SimpleFieldSet fs = node.exportDarknetPublicFieldSet();
			fs.writeTo(w);
		} else if(command.equals("GetConfig")) {
			w.write("MyConfig\n");
			SimpleFieldSet fs = node.config.exportFieldSet();
			fs.writeTo(w);
		} else if(command.equals("GetConnectionStatusCounts")) {
			Map<Integer, Integer> countsByStatus = node.peers.getPeerCountsByStatus();
			SimpleFieldSet fs = new SimpleFieldSet(true);
			for(Map.Entry<Integer, Integer> entry : countsByStatus.entrySet()) {
				fs.put(Integer.toString(entry.getKey()), entry.getValue());
			}
			w.write("ConnectionStatusCounts\n");
			fs.writeTo(w);
		} else if(command.equals("GetFCPStatsSummary")) {
			SimpleFieldSet fs = node.nodeStats.exportVolatileFieldSet();
			w.write("FCPStatsSummary\n");
			fs.writeTo(w);
		} else if(command.equals("GetConnections")) {
			w.write("Connections\n");
			PeerNode[] peers = node.peers.myPeers;
			SimpleFieldSet fs = new SimpleFieldSet(true);
			int x = 0;
			for(PeerNode p : peers) {
				SimpleFieldSet peerFS = new SimpleFieldSet(true);
				peerFS.put("noderef", p.exportFieldSet());
				peerFS.put("volatile", p.exportVolatileFieldSet());
				peerFS.put("metadata", p.exportMetadataFieldSet());
				peerFS.put("status", p.getPeerNodeStatus());
				fs.put("peer" + x, peerFS);
				x++;
			}
			fs.writeTo(w);
		} else if(command.equals("GetLogsList")) {
			w.write("LogsList\n");
			FileLoggerHook loggerHook;
			loggerHook = Node.logConfigHandler.getFileLoggerHook();
			if(loggerHook == null) {
				w.write("ErrorNoLogger\n");
				return false;
			}
			SimpleFieldSet fs = loggerHook.listAvailableLogs();
			fs.writeTo(w);
		} else if(command.startsWith("GetLog:")) {
			String date = command.substring("GetLog:".length());
			DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.UK);
			df.setTimeZone(TimeZone.getTimeZone("GMT"));
			Date d;
			try {
				d = df.parse(date);
			} catch (ParseException e) {
				System.err.println("Cannot parse date");
				w.write("ErrorCannotParseDate\n");
				return false;
			}
			System.out.println("Coordinator asked for log at time "+d);
			w.flush();
			FileLoggerHook loggerHook;
			loggerHook = Node.logConfigHandler.getFileLoggerHook();
			if(loggerHook == null) {
				w.write("ErrorNoLogger\n");
				return false;
			}
			w.write("Logs:\n");
			w.flush();
			loggerHook.sendLogByContainedDate(d.getTime(), os, null);
		} else if(command.startsWith("GetLogFiltered:")) {
			String[] split = command.split(":");
			if(split.length != 3) {
				w.write("ErrorTooFewFields");
				return false;
			}
			String date = split[1];
			long d;
			try {
				d = Long.parseLong(date);
			} catch (NumberFormatException e) {
				System.err.println("Cannot parse date");
				w.write("ErrorCannotParseDate\n");
				return false;
			}
			String regex = split[2];
			Pattern p = null;
			try {
				p = Pattern.compile(regex);
			} catch (PatternSyntaxException e) {
				System.err.println("Cannot parse regex");
				w.write("ErrorCannotParseRegex\n");
				return false;
			}
			System.out.println("Coordinator asked for log at time "+d+" filtered with regex \""+regex+"\"");
			w.flush();
			FileLoggerHook loggerHook;
			loggerHook = Node.logConfigHandler.getFileLoggerHook();
			if(loggerHook == null) {
				w.write("ErrorNoLogger\n");
				return false;
			}
			w.write("Logs:\n");
			w.flush();
			loggerHook.sendLogByContainedDate(d, os, p);
		}
		// FIXME fetch recent error messages
		return false;
	}

	public void waitForConnectivity() {
		boolean sleep = false;
		long sleepTime = 1000;
		long maxSleepTime = 60 * 60 * 1000;
		while(true) {
			if(sleep) {
				try {
					System.out.println("Sleeping for "+sleepTime+"ms");
					Thread.sleep(sleepTime);
					sleepTime *= 2;
					if(sleepTime > maxSleepTime)
						sleepTime = maxSleepTime;
				} catch (InterruptedException e1) {
					// Ignore
				}
			}
			sleep = true;
			System.err.println("Trying to verify testnet connectivity with coordinator...");
			if(verifyConnectivity())
				return;
			WrapperManager.signalStarting((int)sleepTime + 30*1000);
		}
	}
	
	public static long makeID() {
		boolean sleep = false;
		long sleepTime = 1000;
		long maxSleepTime = 60 * 60 * 1000;
		while(true) {
			if(sleep) {
				try {
					Thread.sleep(sleepTime);
					sleepTime *= 2;
					if(sleepTime > maxSleepTime)
						sleepTime = maxSleepTime;
				} catch (InterruptedException e1) {
					// Ignore
				}
			}
			System.err.println("Trying to contact testnet coordinator to get an ID");
			sleep = true;
			Socket client = null;
			try {
				client = new Socket(serverAddress, serverPort);
				InputStream is = client.getInputStream();
				OutputStream os = client.getOutputStream();
				InputStreamReader isr = new InputStreamReader(new BufferedInputStream(is), "UTF-8");
				BufferedReader br = new BufferedReader(isr);
				OutputStreamWriter osw = new OutputStreamWriter(new BufferedOutputStream(os));
				osw.write("GENERATE\n");
				osw.flush();
				String returned = br.readLine();
				if(returned == null)
					throw new EOFException();
				final String chop = "GENERATEDID:";
				if(!returned.startsWith(chop)) {
					throw new IOException("Bogus return: \""+returned+"\" - expected GENERATEDID:");
				}
				returned = returned.substring(chop.length());
				try {
					return Long.parseLong(returned);
				} catch (NumberFormatException e) {
					throw new IOException("Bogus return wasn't a number: \""+returned+"\"");
				}
			} catch (UnknownHostException e) {
				System.err.println("Unknown host, waiting...");
				continue;
			} catch (IOException e) {
				System.err.println("Unable to connect: "+e+" , waiting...");
				continue;
			} finally {
				try {
					if(client != null)
						client.close();
				} catch (IOException e) {
					// Ignore
				}
			}
			
		}
	}
	
	
}