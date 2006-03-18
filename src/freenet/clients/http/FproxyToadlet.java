package freenet.clients.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.MIMEType;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.pluginmanager.HTTPRequest;
import freenet.pluginmanager.PproxyToadlet;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.MultiValueTable;

public class FproxyToadlet extends Toadlet {
	
	final byte[] random;
	
	// ?force= links become invalid after 2 hours.
	long FORCE_GRAIN_INTERVAL = 60*60*1000;
	
	public FproxyToadlet(HighLevelSimpleClient client, byte[] random) {
		super(client);
		this.random = random;
	}
	
	public String supportedMethods() {
		return "GET";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String ks = uri.getPath();
		
		if (ks.equals("/")) {
			RedirectException re = new RedirectException();
			try {
				re.newuri = new URI("/welcome/");
			} catch (URISyntaxException e) {
				// HUH!?!
			}
			throw re;
		}
		
	}
	
	public void handleGet(URI uri, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		//String ks = uri.toString();
		String ks = uri.getPath();
		
		HTTPRequest httprequest = new HTTPRequest(uri);
		
		if (ks.equals("/")) {
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
			
			// Now, is it safe?
			
			Bucket data = result.asBucket();
			
			String typeName = result.getMimeType();
			
			String reqParam = httprequest.getParam("type", null);
			
			if(reqParam != null)
				typeName = reqParam;
			
			Logger.minor(this, "Type: "+typeName+" ("+result.getMimeType()+" "+reqParam+")");
			
			long now = System.currentTimeMillis();
			
			String forceString = httprequest.getParam("force");
			boolean force = false;
			if(forceString != null) {
				if(forceString.equals(getForceValue(key, now)) || 
						forceString.equals(getForceValue(key, now-FORCE_GRAIN_INTERVAL)))
					force = true;
			}
			
			try {
				if(!force)
					data = ContentFilter.filter(data, ctx.getBucketFactory(), typeName);
				
				// Send the data, intact
				writeReply(ctx, 200, typeName, "OK", data);
			} catch (UnsafeContentTypeException e) {
				StringBuffer buf = new StringBuffer();
				ctx.getPageMaker().makeHead(buf, "Potentially Dangerous Content");
				buf.append("<h1>");
				buf.append(e.getHTMLEncodedTitle());
				buf.append("</h1>\n");
				buf.append(e.getExplanation());
				buf.append("<p>Your options are:</p><ul>\n");
				buf.append("<li><a href=\"/"+key.toString(false)+"?type=text/plain\">Click here</a> to open the file as plain text (this should not be dangerous, but it may be garbled).</li>\n");
				// FIXME: is this safe? See bug #131
				buf.append("<li><a href=\"/"+key.toString(false)+"?type=application/x-msdownload\">Click here</a> to force your browser to download the file to disk.</li>\n");
				buf.append("<li><a href=\"/"+key.toString(false)+"?force="+getForceValue(key, now)+"\">Click here</a> to open the file as "+HTMLEncoder.encode(typeName)+".</li>\n");
				buf.append("<li><a href=\"/\">Click here</a> to go to the FProxy home page.</li>\n");
				buf.append("</ul>");
				ctx.getPageMaker().makeTail(buf);
				writeReply(ctx, 200, "text/html", "OK", buf.toString());
			}
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

	private String getForceValue(FreenetURI key, long time) {
		try {
			MessageDigest md5 = MessageDigest.getInstance("SHA-256");
			md5.update(random);
			md5.update(key.toString(false).getBytes());
			md5.update(Long.toString(time / FORCE_GRAIN_INTERVAL).getBytes());
			return HexUtil.bytesToHex(md5.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
	}

	public static void maybeCreateFproxyEtc(Node node, Config config) throws IOException, InvalidConfigValueException {
		
		SubConfig fproxyConfig = new SubConfig("fproxy", config);
		
		try {
			SimpleToadletServer server = new SimpleToadletServer(fproxyConfig, node);
			
			HighLevelSimpleClient client = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
			
			node.setToadletContainer(server);
			byte[] random = new byte[32];
			node.random.nextBytes(random);
			FproxyToadlet fproxy = new FproxyToadlet(client, random);
			node.setFproxy(fproxy);
			server.register(fproxy, "/", false);
			
			PproxyToadlet pproxy = new PproxyToadlet(client, node.pluginManager);
			server.register(pproxy, "/plugins/", true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, node);
			server.register(welcometoadlet, "/welcome/", true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config);
			server.register(configtoadlet, "/config/", true);
			
			StaticToadlet statictoadlet = new StaticToadlet(client);
			server.register(statictoadlet, "/static/", true);
			
			SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
			server.register(symlinkToadlet, "/sl/", true);
			
			DarknetConnectionsToadlet darknetToadlet = new DarknetConnectionsToadlet(node, client);
			server.register(darknetToadlet, "/darknet/", true);

		} catch (IOException ioe) {
			Logger.error(node,"Failed to start fproxy: "+ioe, ioe);
		}
		
		fproxyConfig.finishedInitialization();
	}
}
