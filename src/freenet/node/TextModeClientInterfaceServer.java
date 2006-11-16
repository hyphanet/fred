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
    final NodeClientCore core;
//    final HighLevelSimpleClient client;
    final Hashtable streams;
    final File downloadsDir;
    int port;
    String bindTo;
    String allowedHosts;
    boolean isEnabled;
    NetworkInterface networkInterface;

    TextModeClientInterfaceServer(Node node, int port, String bindTo, String allowedHosts) {
    	this.n = node;
    	this.core = n.clientCore;
        this.r = n.random;
        streams = new Hashtable();
        this.downloadsDir = core.downloadDir;
        this.port=port;
        this.bindTo=bindTo;
        this.allowedHosts = allowedHosts;
        this.isEnabled=true;
        core.setTMCI(this);
    }
    
    void start() {
        Thread t = new Thread(this, "Text mode client interface");
        t.setDaemon(true);
        t.start();
    }
    
	public static void maybeCreate(Node node, Config config) throws IOException {
		SubConfig TMCIConfig = new SubConfig("console", config);
		
		NodeClientCore core = node.clientCore;
		
		TMCIConfig.register("enabled", true, 1, true, false, "Enable TMCI", "Whether to enable the TMCI",
				new TMCIEnabledCallback(core));
		TMCIConfig.register("bindTo", "127.0.0.1", 2, true, false, "IP address to bind to", "IP address to bind to",
				new TMCIBindtoCallback(core));
		TMCIConfig.register("allowedHosts", "127.0.0.1,0:0:0:0:0:0:0:1", 2, true, false, "Allowed hosts", "Hostnames or IP addresses that are allowed to connect to the TMCI. May be a comma-separated list of hostnames, single IPs and even CIDR masked IPs like 192.168.0.0/24",
				new TMCIAllowedHostsCallback(core));
		TMCIConfig.register("port", 2323, 1, true, false, "Telnet port", "Telnet port number",
        		new TCMIPortNumberCallback(core));
		TMCIConfig.register("directEnabled", false, 1, true, false, "Enable on stdout/stdin?", "Enable text mode client interface on standard input/output? (.enabled refers to providing a telnet-style server, this runs it over a socket)",
				new TMCIDirectEnabledCallback(core));
		
		boolean TMCIEnabled = TMCIConfig.getBoolean("enabled");
		int port =  TMCIConfig.getInt("port");
		String bind_ip = TMCIConfig.getString("bindTo");
		String allowedHosts = TMCIConfig.getString("allowedHosts");
		boolean direct = TMCIConfig.getBoolean("directEnabled");

		if(TMCIEnabled){
			new TextModeClientInterfaceServer(node, port, bind_ip, allowedHosts).start();
			Logger.normal(core, "TMCI started on "+bind_ip+ ':' +port);
			System.out.println("TMCI started on "+bind_ip+ ':' +port);
		}
		else{
			Logger.normal(core, "Not starting TMCI as it's disabled");
			System.out.println("Not starting TMCI as it's disabled");
		}
		
		if(direct) {
	        HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
			TextModeClientInterface directTMCI =
				new TextModeClientInterface(node, client, core.downloadDir, System.in, System.out);
			Thread t = new Thread(directTMCI, "Direct text mode interface");
			t.setDaemon(true);
			t.start();
			core.setDirectTMCI(directTMCI);
		}
		
		TMCIConfig.finishedInitialization();
	}

    
    static class TMCIEnabledCallback implements BooleanCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIEnabledCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public boolean get() {
    		return core.getTextModeClientInterface() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }

    static class TMCIDirectEnabledCallback implements BooleanCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIDirectEnabledCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public boolean get() {
    		return core.getDirectTMCI() != null;
    	}
    	
    	public void set(boolean val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		// FIXME implement - see bug #122
    		throw new InvalidConfigValueException("Cannot be updated on the fly");
    	}
    }
    
    static class TMCIBindtoCallback implements StringCallback {
    	
    	final NodeClientCore core;
    	
    	TMCIBindtoCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public String get() {
    		if(core.getTextModeClientInterface()!=null)
    			return core.getTextModeClientInterface().bindTo;
    		else
    			return "127.0.0.1";
    	}
    	
    	public void set(String val) throws InvalidConfigValueException {
    		if(val.equals(get())) return;
    		try {
				core.getTextModeClientInterface().networkInterface.setBindTo(val);
				core.getTextModeClientInterface().bindTo = val;
			} catch (IOException e) {
				throw new InvalidConfigValueException("could not change bind to!");
			}
    	}
    }
    
    static class TMCIAllowedHostsCallback implements StringCallback {

    	private final NodeClientCore core;
    	
    	public TMCIAllowedHostsCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
		public String get() {
			if (core.getTextModeClientInterface() != null) {
				return core.getTextModeClientInterface().allowedHosts;
			}
			return "127.0.0.1";
		}

		public void set(String val) {
			if (!val.equals(get())) {
				core.getTextModeClientInterface().networkInterface.setAllowedHosts(val);
				core.getTextModeClientInterface().allowedHosts = val;
			}
		}
    	
    }

    static class TCMIPortNumberCallback implements IntCallback{
    	
    	final NodeClientCore core;
    	
    	TCMIPortNumberCallback(NodeClientCore core) {
    		this.core = core;
    	}
    	
    	public int get() {
    		if(core.getTextModeClientInterface()!=null)
    			return core.getTextModeClientInterface().port;
    		else
    			return 2323;
    	}
    	
    	// TODO: implement it
    	public void set(int val) throws InvalidConfigValueException {
    		if(val == get()) return;
    		core.getTextModeClientInterface().setPort(val);
    	}
    }

    /**
     * Read commands, run them
     */
    public void run() {
    	while(true) {
    		int curPort = port;
    		String tempBindTo = this.bindTo;
    		try {
    			networkInterface = new NetworkInterface(curPort, tempBindTo, allowedHosts);
    		} catch (IOException e) {
    			Logger.error(this, "Could not bind to TMCI port: "+tempBindTo+ ':' +port);
    			System.err.println("Could not bind to TMCI port: "+tempBindTo+ ':' +port);
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
				if(!(this.bindTo.equals(tempBindTo))) break;
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
