package freenet.node;

//import java.io.BufferedReader;
import java.io.IOException;
//import java.io.InputStream;
//import java.io.InputStreamReader;
//import java.io.OutputStream;
//import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.Socket;
//import java.text.DateFormat;
//import java.text.ParseException;
//import java.util.Date;
//import java.util.Locale;
//import java.util.TimeZone;

import freenet.support.Logger;

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
public class TestnetStatusUploader implements Runnable {

	public TestnetStatusUploader(Node node2, int updateInterval) {
		this.node = node2;
		this.updateInterval = updateInterval;
		Logger.error(this, "STARTING TESTNET STATUSUPLOADER!");
		Logger.error(this, "ANONYMITY MODE: OFF");
		System.err.println("STARTING TESTNET STATUSUPLOADER!");
		System.err.println("ANONYMITY MODE: OFF");
		System.err.println("You have no anonymity. Thank you for running a testnet node, this will help the developers to efficiently debug Freenet, by letting them (and anyone else who knows how!!) automatically fetch your log files.");
		System.err.println("We repeat: YOU HAVE NO ANONYMITY WHATSOEVER. DO NOT POST ANYTHING YOU DO NOT WANT TO BE ASSOCIATED WITH.");
		System.err.println("If you want a real freenet node, with anonymity, turn off testnet mode.");
		uploaderThread = new Thread(this, "TestnetStatusUploader thread");
		uploaderThread.setDaemon(true);
		uploaderThread.start();
	}
	
	private final Node node;
	private final Thread uploaderThread;
	private final int updateInterval;
	private Socket client;
	
	public void run() {
		// Set up client socket
		try
		{
			//thread loop
			
			while(true){
			
				client = new Socket("sleon.dyndns.org", 23415);
				PrintStream output = new PrintStream(client.getOutputStream());
				output.println(node.getStatus());
				output.close();
	
				client.close();
				
				try{
					Thread.sleep(updateInterval);
						
				//how i love java 
				}catch (InterruptedException e){
					return;
					
				}
				
			}
			
		}catch (IOException e){
			Logger.error(this, "Could not open connection to the uploadhost");
			System.err.println("Could not open connection to the uploadhost");
			return;
		}

	}
	
	
}