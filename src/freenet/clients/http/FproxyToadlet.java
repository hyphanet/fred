package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.StringCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.pluginmanager.HTTPRequest;
import freenet.pluginmanager.PproxyToadlet;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class FproxyToadlet extends Toadlet {
	
	public FproxyToadlet(HighLevelSimpleClient client, String CSSName) {
		super(client, CSSName);
	}
	
	public String supportedMethods() {
		return "GET";
	}

	public void handleGet(URI uri, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		//String ks = uri.toString();
		String ks = uri.getPath();
		
		
		if (ks.equals("/")) {
			HTTPRequest httprequest = new HTTPRequest(uri);
			if (httprequest.isParameterSet("key")) {
				MultiValueTable headers = new MultiValueTable();
				
				headers.put("Location", "/"+httprequest.getParam("key"));
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			
			RedirectException re = new RedirectException();
			try {
				re.newuri = new URI("/welcome/");
			} catch (URISyntaxException e) {
				// HUH!?!
			}
			throw re;
		}
		
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			this.writeReply(ctx, 400, "text/html", "Invalid key", "<html><head><title>Invalid key</title></head><body>Expected a freenet key, but got "+HTMLEncoder.encode(ks)+"</body></html>");
			return;
		}
		try {
			Logger.minor(this, "Fproxy fetching "+key);
			FetchResult result = fetch(key);
			writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (FetchException e) {
			String msg = e.getMessage();
			String extra = "";
			if(e.errorCodes != null)
				extra = "<pre>"+e.errorCodes.toVerboseString()+"</pre>";
			this.writeReply(ctx, 500 /* close enough - FIXME probably should depend on status code */,
					"text/html", msg, "<html><head><title>"+msg+"</title></head><body>Error: "+HTMLEncoder.encode(msg)+extra+"</body></html>");
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			String msg = "<html><head><title>Internal Error</title></head><body><h1>Internal Error: please report</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + sw.toString() + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", "Internal Error", msg);
		}
	}

	static class FproxyEnabledCallback implements BooleanCallback {
		
		final Node node;
		
		FproxyEnabledCallback(Node n) {
			this.node = n;
		}
		
		public boolean get() {
			return node.getFproxy() != null;
		}
		public void set(boolean val) throws InvalidConfigValueException {
			if(val == get()) return;
			throw new InvalidConfigValueException("Cannot change fproxy enabled/disabled after startup");
		}
	}
	
	static final int DEFAULT_FPROXY_PORT = 8888;
	
	static class FproxyPortCallback implements IntCallback {
		
		final Node node;
		
		FproxyPortCallback(Node n) {
			this.node = n;
		}
		
		public int get() {
			SimpleToadletServer f = node.getToadletContainer();
			if(f == null) return DEFAULT_FPROXY_PORT;
			return f.port;
		}
		
		public void set(int port) throws InvalidConfigValueException {
			if(port != get())
				throw new InvalidConfigValueException("Cannot change fproxy port number on the fly");
		}
	}
	
	static class FproxyBindtoCallback implements StringCallback {
		
		final Node node;
		
		FproxyBindtoCallback(Node n) {
			this.node = n;
		}
		
		public String get() {
			SimpleToadletServer f = node.getToadletContainer();
			if(f == null) return "127.0.0.1";
			return f.bindto;
		}
		
		public void set(String bindto) throws InvalidConfigValueException {
			if(bindto != get())
				throw new InvalidConfigValueException("Cannot change fproxy bind address on the fly");
		}
	}
	
	// FIXME: Not changed on the fly :-S Should be done in SimpleToadletServer
	static class FproxyCSSNameCallback implements StringCallback {
		
		final Node node;
		
		FproxyCSSNameCallback(Node n) {
			this.node = n;
		}
		
		public String get() {
			return node.getFproxy().getCSSName();
		}
		
		public void set(String CSSName) throws InvalidConfigValueException {
			FproxyToadlet f = node.getFproxy();
			f.setCSSName(CSSName);
		}
	}
	
	public static void maybeCreateFproxyEtc(Node node, Config config) throws IOException {
		
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		
		fproxyConfig.register("enabled", true, 1, true, "Enable fproxy?", "Whether to enable fproxy and related HTTP services",
				new FproxyEnabledCallback(node));
		
		boolean fproxyEnabled = fproxyConfig.getBoolean("enabled");
		
		if(!fproxyEnabled) {
			fproxyConfig.finishedInitialization();
			Logger.normal(node, "Not starting Fproxy as it's disabled");
			return;
		}
		
		fproxyConfig.register("port", DEFAULT_FPROXY_PORT, 2, true, "Fproxy port number", "Fproxy port number",
				new FproxyPortCallback(node));
		fproxyConfig.register("bindto", "127.0.0.1", 2, true, "IP address to bind to", "IP address to bind to",
				new FproxyBindtoCallback(node));
		fproxyConfig.register("css", "clean", 1, true, "CSS Name", "Name of the CSS Fproxy should use",
				new FproxyCSSNameCallback(node));
		
		int port = fproxyConfig.getInt("port");
		String bind_ip = fproxyConfig.getString("bindto");
		String CSSName = fproxyConfig.getString("css");
		
		System.out.println("Starting fproxy on port "+(port));
		Logger.normal(node,"Starting fproxy on "+bind_ip+":"+port);
		
		try {
			SimpleToadletServer server = new SimpleToadletServer(port, bind_ip);
			node.setToadletContainer(server);
			FproxyToadlet fproxy = new FproxyToadlet(node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), CSSName);
			node.setFproxy(fproxy);
			server.register(fproxy, "/", false);
			
			PproxyToadlet pproxy = new PproxyToadlet(node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), node.pluginManager, CSSName);
			server.register(pproxy, "/plugins/", true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), node, CSSName);
			server.register(welcometoadlet, "/welcome/", true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), config, CSSName);
			server.register(configtoadlet, "/config/", true);
			
			StaticToadlet statictoadlet = new StaticToadlet(node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), CSSName);
			server.register(statictoadlet, "/static/", true);
		} catch (IOException ioe) {
			Logger.error(node,"Failed to start fproxy on "+bind_ip+":"+port);
		}
		
		fproxyConfig.finishedInitialization();
	}
}
