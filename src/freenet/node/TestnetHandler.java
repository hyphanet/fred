package freenet.node;

import java.io.BufferedReader;
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

import freenet.support.Logger;

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

	public TestnetHandler(Node node2, int testnetPort) {
		this.node = node2;
		this.testnetPort = testnetPort;
		Logger.error(this, "STARTING TESTNET SERVER!");
		Logger.error(this, "ANONYMITY MODE: OFF");
		System.err.println("STARTING TESTNET SERVER!");
		System.err.println("ANONYMITY MODE: OFF");
		System.err.println("You have no anonymity. Thank you for running a testnet node, this will help the developers to efficiently debug Freenet, by letting them (and anyone else who knows how!!) automatically fetch your log files.");
		System.err.println("We repeat: YOU HAVE NO ANONYMITY WHATSOEVER. DO NOT POST ANYTHING YOU DO NOT WANT TO BE ASSOCIATED WITH.");
		System.err.println("If you want a real freenet node, with anonymity, turn off testnet mode.");
		System.err.println("Note, this node will not connect to non-testnet nodes, for security reasons. You can of course run a testnet node and a non-testnet node separately.");
		serverThread = new Thread(this, "Testnet handler thread");
		serverThread.setDaemon(true);
		serverThread.start();
	}
	
	private final Node node;
	private final Thread serverThread;
	private final int testnetPort;
	
	public void run() {
		// Set up server socket
		ServerSocket server;
		try {
			server = new ServerSocket(testnetPort);
		} catch (IOException e) {
			Logger.error(this, "Could not bind to testnet port: "+testnetPort);
			System.err.println("Could not bind to testnet port: "+testnetPort);
			System.exit(Node.EXIT_TESTNET_FAILED);
			return;
		}
		while(true) {
			try {
				Socket s = server.accept();
				TestnetSocketHandler tsh = new TestnetSocketHandler(s);
			} catch (IOException e) {
				Logger.error(this, "Testnet failed to accept socket: "+e, e);
			}
			
		}
	}
	
	public class TestnetSocketHandler implements Runnable {

		private Socket s;
		
		public TestnetSocketHandler(Socket s2) {
			this.s = s2;
			Thread t = new Thread(this, "Testnet handler for "+s.getInetAddress()+" at "+System.currentTimeMillis());
			t.setDaemon(true);
			t.start();
		}

		public void run() {
			InputStream is = null;
			OutputStream os = null;
			try {
				is = s.getInputStream();
				os = s.getOutputStream();
				// Read command
				InputStreamReader isr = new InputStreamReader(is, "ISO-8859-1");
				BufferedReader br = new BufferedReader(isr);
				String command = br.readLine();
				Logger.minor(this, "Command: "+command);
				if(command.equalsIgnoreCase("LIST")) {
					Logger.minor(this, "Listing available logs");
					OutputStreamWriter osw = new OutputStreamWriter(os, "ISO-8859-1");
					node.fileLoggerHook.listAvailableLogs(osw);
					osw.close();
				} else if(command.startsWith("GET:")) {
					Logger.minor(this, "Sending log: "+command);
					String date = command.substring("GET:".length());
					DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ENGLISH);
					df.setTimeZone(TimeZone.getTimeZone("GMT"));
					Date d;
					try {
						d = df.parse(date);
					} catch (ParseException e) {
						Logger.minor(this, "Cannot parse: "+e+" for "+date);
						return;
					}
					node.fileLoggerHook.sendLogByContainedDate(d.getTime(), os);
				} else {
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

}
