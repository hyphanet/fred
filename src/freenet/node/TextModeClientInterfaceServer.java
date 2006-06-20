package freenet.node;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Hashtable;

import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.NetworkInterface;
import freenet.support.Logger;

public class TextModeClientInterfaceServer implements Runnable {

    final RandomSource r;
    final Node n;
//    final HighLevelSimpleClient client;
    final Hashtable streams;
    final File downloadsDir;
    int port;
    final String bindTo;
    String allowedHosts;
    boolean isEnabled;
    NetworkInterface networkInterface;

    TextModeClientInterfaceServer(Node n, int port, String bindTo, String allowedHosts) {
        this.n = n;
        this.r = n.random;
        streams = new Hashtable();
        this.downloadsDir = n.downloadDir;
        this.port=port;
        this.bindTo=bindTo;
        this.allowedHosts = allowedHosts;
        this.isEnabled=true;
        n.setTMCI(this);
    }
    
    void start() {
        Thread t = new Thread(this, "Text mode client interface");
        t.setDaemon(true);
        t.start();
    }
    
	public static void maybeCreate(Node node, Config config) throws IOException {
		SubConfig TMCIConfig = new SubConfig("console", config);
		
		TMCIConfig.register("enabled", true, 1, true, "Enable TMCI", "Whether to enable the TMCI",
				new TMCIEnabledCallback(node));
		TMCIConfig.register("bindTo", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind to",
				new TMCIBindtoCallback(node));
		TMCIConfig.register("allowedHosts", "127.0.0.1", 2, true, "Allowed hosts", "Hostnames or IP addresses that are allowed to connect to the TMCI. May be a comma-separated list of hostnames, single IPs and even CIDR masked IPs like 192.168.0.0/24",
				new TMCIAllowedHostsCallback(node));
		TMCIConfig.register("port", 2323, 1, true, "Telnet port", "Telnet port number",
        		new TCMIPortNumberCallback(node));
		TMCIConfig.register("directEnabled", false, 1, true, "Enable on stdout/stdin?", "Enable text mode client interface on standard input/output? (.enabled refers to providing a telnet-style server, this runs it over a socket)",
				new TMCIDirectEnabledCallback(node));
		
		boolean TMCIEnabled = TMCIConfig.getBoolean("enabled");
		int port =  TMCIConfig.getInt("port");
		String bind_ip = TMCIConfig.getString("bindTo");
		String allowedHosts = TMCIConfig.getString("allowedHosts");
		boolean direct = TMCIConfig.getBoolean("directEnabled");

		if(TMCIEnabled){
			new TextModeClientInterfaceServer(node, port, bind_ip, allowedHosts).start();
			Logger.normal(node, "TMCI started on "+bind_ip+":"+port);
			System.out.println("TMCI started on "+bind_ip+":"+port);
		}
		else{
			Logger.normal(node, "Not starting TMCI as it's disabled");
			System.out.println("Not starting TMCI as it's disabled");
		}
		
		if(direct) {
	        HighLevelSimpleClient client = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
			TextModeClientInterface directTMCI =
				new TextModeClientInterface(node, client, node.downloadDir, System.in, System.out);
			Thread t = new Thread(directTMCI, "Direct text mode interface");
			t.setDaemon(true);
			t.start();
			node.setDirectTMCI(directTMCI);
		}
		
		TMCIConfig.finishedInitialization();
	}

    
    static class TMCIEnabledCallback implements BooleanCallback {
    	
    	final Node node;
    	
    	TMCIEnabledCallback(Node n) {
    		this.node = n;
    	}
    	
    	public boolean get() {
    		return node.getTextModeClientInterface() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }

    static class TMCIDirectEnabledCallback implements BooleanCallback {
    	
    	final Node node;
    	
    	TMCIDirectEnabledCallback(Node n) {
    		this.node = n;
    	}
    	
    	public boolean get() {
    		return node.getDirectTMCI() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }
    
    static class TMCIBindtoCallback implements StringCallback {
    	
    	final Node node;
    	
    	TMCIBindtoCallback(Node n) {
    		this.node = n;
    	}
    	
    	public String get() {
    		if(node.getTextModeClientInterface()!=null)
    			return node.getTextModeClientInterface().bindTo;
    		else
    			return "127.0.0.1";
    	}
    	
    	public void set(String val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }
    
    static class TMCIAllowedHostsCallback implements StringCallback {

    	private final Node node;
    	
    	public TMCIAllowedHostsCallback(Node node) {
    		this.node = node;
    	}
    	
		public String get() {
			if (node.getTextModeClientInterface() != null) {
				return node.getTextModeClientInterface().allowedHosts;
			}
			return "127.0.0.1";
		}

		public void set(String val) {
			if (!val.equals(get())) {
				node.getTextModeClientInterface().networkInterface.setAllowedHosts(val);
				node.getTextModeClientInterface().allowedHosts = val;
			}
		}
    	
    }

    static class TCMIPortNumberCallback implements IntCallback{
    	
    	final Node node;
    	
    	TCMIPortNumberCallback(Node n) {
    		this.node = n;
    	}
    	
    	public int get() {
    		if(node.getTextModeClientInterface()!=null)
    			return node.getTextModeClientInterface().port;
    		else
    			return 2323;
    	}
    	
    	// TODO: implement it
    	public void set(int val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		node.getTextModeClientInterface().setPort(val);
    	}
    }

    /**
     * Read commands, run them
     */
    public void run() {
    	while(true) {
    		int curPort = port;
    		String bindTo = this.bindTo;
    		try {
    			networkInterface = new NetworkInterface(curPort, bindTo, allowedHosts);
    		} catch (IOException e) {
    			Logger.error(this, "Could not bind to TMCI port: "+bindTo+":"+port);
    			System.exit(-1);
    			return;
    		}
    		try {
    			networkInterface.setSoTimeout(1000);
    		} catch (SocketException e1) {
    			Logger.error(this, "Could not set timeout: "+e1, e1);
    			System.err.println("Could not start TMCI: "+e1);
    			e1.printStackTrace();
    			return;
    		}
    		while(isEnabled) {
    			// Maybe something has changed?
				if(port != curPort) break;
				if(!(this.bindTo.equals(bindTo))) break;
    			try {
    				Socket s = networkInterface.accept();
    				InputStream in = s.getInputStream();
    				OutputStream out = s.getOutputStream();
    				
    				TextModeClientInterface tmci = 
					new TextModeClientInterface(this, in, out);
    				
    				Thread t = new Thread(tmci, "Text mode client interface handler for "+s.getPort());
    				
    				t.setDaemon(true);
    				
    				t.start();
    				
    			} catch (SocketTimeoutException e) {
    				// Ignore and try again
    			} catch (SocketException e){
    				Logger.error(this, "Socket error : "+e, e);
    			} catch (IOException e) {
    				Logger.error(this, "TMCI failed to accept socket: "+e, e);
    			}
    		}
    		try{
    			networkInterface.close();
    		}catch (IOException e){
    			Logger.error(this, "Error shuting down TMCI", e);
    		}
    	}
    }

	public void setPort(int val) {
		port = val;
	}
	
}
