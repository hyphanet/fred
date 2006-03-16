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
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.pluginmanager.HTTPRequest;
import freenet.pluginmanager.PproxyToadlet;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class FproxyToadlet extends Toadlet {
	
	public FproxyToadlet(HighLevelSimpleClient client, CSSNameCallback server) {
		super(client, server);
	}
	
	public String supportedMethods() {
		return "GET";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(data.size() > 1024*1024) {
			this.writeReply(ctx, 400, "text/plain", "Too big", "Too much data, config servlet limited to 1MB");
			return;
		}
		byte[] d = BucketTools.toByteArray(data);
		String s = new String(d, "us-ascii");
		HTTPRequest request;
		try {
			request = new HTTPRequest("/", s);
		} catch (URISyntaxException e) {
			Logger.error(this, "Impossible: "+e, e);
			return;
		}
		
		if(request.hasParameters() && request.getParam("exit").equalsIgnoreCase("true")){	
			System.out.println("Goodbye.");
			writeReply(ctx, 200, "text/html", "OK", mkForwardPage(ctx, "Shutting down the node", "" , "/", 5));
			System.exit(0);
		}
		
	}
	
	public void handleGet(URI uri, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		//String ks = uri.toString();
		String ks = uri.getPath();
		
		HTTPRequest request = new HTTPRequest(uri);
		
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

	public static void maybeCreateFproxyEtc(Node node, Config config) throws IOException, InvalidConfigValueException {
		
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		
		try {
			SimpleToadletServer server = new SimpleToadletServer(fproxyConfig, node);
			
			HighLevelSimpleClient client = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
			
			node.setToadletContainer(server);
			FproxyToadlet fproxy = new FproxyToadlet(client, server);
			node.setFproxy(fproxy);
			server.register(fproxy, "/", false);
			
			PproxyToadlet pproxy = new PproxyToadlet(client, node.pluginManager, server);
			server.register(pproxy, "/plugins/", true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, node, server);
			server.register(welcometoadlet, "/welcome/", true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config, server);
			server.register(configtoadlet, "/config/", true);
			
			StaticToadlet statictoadlet = new StaticToadlet(client, server);
			server.register(statictoadlet, "/static/", true);
			
			SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, server, node);
			server.register(symlinkToadlet, "/sl/", true);
			
			DarknetConnectionsToadlet darknetToadlet = new DarknetConnectionsToadlet(node, client, server);
			server.register(darknetToadlet, "/darknet/", true);

		} catch (IOException ioe) {
			Logger.error(node,"Failed to start fproxy on "+fproxyConfig.getString("bindTo")+":"+fproxyConfig.getInt("port"));
		}
		
		fproxyConfig.finishedInitialization();
	}
}
