package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;

import freenet.io.NetworkInterface;
import freenet.support.Executor;
import freenet.support.PooledExecutor;
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
	final Executor executor;
	
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
		networkInterface = NetworkInterface.create(PORT, "0.0.0.0", "0.0.0.0/0", executor, true);
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
		System.err.println("Next counter is: "+counter);
	}

	public static void main(String[] args) throws IOException {
		System.err.println("Testnet controller starting up");
		TestnetController controller = new TestnetController();
		controller.run();
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
			try {
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
					} else if(line.startsWith("VERIFY:")) {
						String[] split = line.split(":");
						if(split.length < 3) continue;
						long testnetNodeID;
						int port;
						try {
							testnetNodeID = Long.parseLong(split[1]);
						} catch (NumberFormatException e) {
							osw.write("FAILED:VERIFY:No valid node ID\n");
							osw.flush();
							continue;
						}
						try {
							port = Integer.parseInt(split[2]);
						} catch (NumberFormatException e) {
							osw.write("FAILED:VERIFY:No valid port number\n");
							osw.flush();
							continue;
						}
						System.out.println("Verifying connectivity to node ID "+testnetNodeID+" on port "+port+" from "+sock.getInetAddress());
						if(!logVerify(testnetNodeID, port)) {
							osw.write("FAILED:VERIFY:No such ID\n");
							osw.flush();
							continue;
						}
						Socket testSocket = null;
						try {
							InetAddress addr = sock.getInetAddress();
							// FIXME ridiculous cheat for running controller and testnet node on same system.
							if(addr.getHostAddress().startsWith("192.168."))
								addr = InetAddress.getByName("amphibian.dyndns.org");
							testSocket = new Socket(addr, port);
							fetchRegularStuff(testnetNodeID, port, testSocket);
							osw.write("OK\n");
							osw.flush();
						} catch (IOException e) {
							// Grrrr
							osw.write("FAILED:VERIFY:Failed to connect to testnet port\n");
							osw.flush();
							continue;
						} finally {
							testSocket.close();
						}
					} else {
						// Do nothing. Read the next line.
					}
				}
			} catch (IOException e) {
				// Grrrr.
			} finally {
				try {
					sock.close();
				} catch (IOException e) {
					// Ignore.
				}
			}
		}

	}

	public synchronized long generateID() throws IOException {
		long newID = counter++;
		File dir = getDir(newID);
		dir.mkdirs();
		if(!dir.exists())
			throw new IOException();
		return newID;
	}

	/** Fetch and record things like the node's noderef.
	 * Rotate once the log gets huge. */
	public void fetchRegularStuff(long testnetNodeID, int port2,
			Socket testSocket) {
		// TODO Auto-generated method stub
		
	}

	public boolean logVerify(long testnetNodeID, int port) {
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
			osw.write(""+port+":"+System.currentTimeMillis()+"\n");
			osw.flush();
		} catch (IOException e) {
			System.err.println("Failed to write verify log: "+e);
			e.printStackTrace();
		} finally {
			if(fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					System.err.println("Failed to log verify attempt: "+e);
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
