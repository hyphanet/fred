package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.support.Base64;
import freenet.support.Bucket;
import freenet.support.HTMLEncoder;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;

public class FProxyToadlet extends Toadlet {
	
	final byte[] random;
	final Node node;
	
	// ?force= links become invalid after 2 hours.
	long FORCE_GRAIN_INTERVAL = 60*60*1000;
	/** Maximum size for transparent pass-through, should be a config option */
	static final long MAX_LENGTH = 2*1024*1024; // 2MB
	
	public FProxyToadlet(HighLevelSimpleClient client, byte[] random, Node node) {
		super(client);
		this.random = random;
		client.setMaxLength(MAX_LENGTH);
		client.setMaxIntermediateLength(MAX_LENGTH);
		this.node = node;
	}
	
	public String supportedMethods() {
		return "GET";
	}

	public void handlePost(URI uri, Bucket data, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String ks = uri.getPath();
		
		if (ks.equals("/")||ks.startsWith("/servlet/")) {
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
				String querystring = uri.getQuery();
				
				if (querystring == null) {
					re.newuri = new URI("/welcome/");
				} else {
					// TODP possibly a proper URLEncode method
					querystring = querystring.replace(' ', '+');
					re.newuri = new URI("/welcome/?"+querystring);
				}
			} catch (URISyntaxException e) {
				// HUH!?!
			}
			throw re;
		}else if(ks.equals("/favicon.ico")){
			byte[] buf = new byte[1024];
			int len;
			InputStream strm = getClass().getResourceAsStream("staticfiles/favicon.ico");
			
			if (strm == null) {
				this.sendErrorPage(ctx, 404, "Path not found", "The specified path does not exist.");
				return;
			}
			ctx.sendReplyHeaders(200, "OK", null, "image/x-icon", strm.available());
			
			while ( (len = strm.read(buf)) > 0) {
				ctx.writeData(buf, 0, len);
			}
			return;
		}
		
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		
		long maxSize = httprequest.getLongParam("max-size", MAX_LENGTH);
		
		StringBuffer buf = new StringBuffer();
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			ctx.getPageMaker().makeHead(buf, "Invalid key");
			
			buf.append("<div class=\"infobox infobox-error\">\n");
			buf.append("<div class=\"infobox-header\">\n");
			buf.append("Invalid key\n");
			buf.append("</div>\n");
			buf.append("<div class=\"infobox-content\">\n");
			
			buf.append("Expected a freenet key, but got "+HTMLEncoder.encode(ks)+"\n");		
			ctx.getPageMaker().makeBackLink(buf,ctx);
			buf.append("<br><a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
			buf.append("</div>\n");
			buf.append("</div>\n");
			
			ctx.getPageMaker().makeTail(buf);
			
			this.writeReply(ctx, 400, "text/html", "Invalid key", buf.toString());
			return;
		}
		try {
			Logger.minor(this, "FProxy fetching "+key);
			FetchResult result = fetch(key, maxSize);
			
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
			boolean forcedownload = false;
			if(forceString != null) {
				if(forceString.equals(getForceValue(key, now)) || 
						forceString.equals(getForceValue(key, now-FORCE_GRAIN_INTERVAL)))
					force = true;
			}

			if(httprequest.isParameterSet("forcedownload")) {
				// Download to disk, this should be safe, and is set when we do "force download to disk" from a dangerous-content-warning page.
				typeName = "application/x-msdownload";
				forcedownload = true;
			}
			
			try {
				if(!force && !forcedownload) {
					data = ContentFilter.filter(data, ctx.getBucketFactory(), typeName, uri, null);
				}
				
				if (forcedownload) {
					MultiValueTable headers = new MultiValueTable();
					
					headers.put("Content-Disposition", "attachment");
					ctx.sendReplyHeaders(200, "OK", headers, typeName, data.size());
					ctx.writeData(data);
				} else {
					// Send the data, intact
					writeReply(ctx, 200, typeName, "OK", data);
				}
			} catch (UnsafeContentTypeException e) {
				ctx.getPageMaker().makeHead(buf, "Potentially Dangerous Content");
				buf.append("<div class=\"infobox infobox-alert\">");
				buf.append("<div class=\"infobox-header\">").append(e.getHTMLEncodedTitle()).append("</div>");
				buf.append("<div class=\"infobox-content\">");
				buf.append(e.getExplanation());
				buf.append("<p>Your options are:</p><ul>\n");
				buf.append("<li><a href=\"/"+key.toString(false)+"?type=text/plain\">Click here</a> to open the file as plain text (this should not be dangerous, but it may be garbled).</li>\n");
				// FIXME: is this safe? See bug #131
				buf.append("<li><a href=\"/"+key.toString(false)+"?forcedownload\">Click here</a> to force your browser to download the file to disk.</li>\n");
				buf.append("<li><a href=\"/"+key.toString(false)+"?force="+getForceValue(key, now)+"\">Click here</a> to open the file as "+HTMLEncoder.encode(typeName)+".</li>\n");
				buf.append("<li><a href=\"/\">Click here</a> to go to the FProxy home page.</li>\n");
				buf.append("</ul></div>");
				buf.append("</div>\n");
				ctx.getPageMaker().makeTail(buf);
				writeReply(ctx, 200, "text/html", "OK", buf.toString());
			}
		} catch (FetchException e) {
			String msg = e.getMessage();
			String extra = "";
			if(e.mode == FetchException.NOT_ENOUGH_METASTRINGS) {
				this.writePermanentRedirect(ctx, "Not enough meta-strings", "/" + URLEncoder.encode(key.toString(false)) + "/");
			} else if(e.newURI != null) {
				this.writePermanentRedirect(ctx, msg, "/"+e.newURI.toString());
			} else if(e.mode == FetchException.TOO_BIG) {
				ctx.getPageMaker().makeHead(buf, "Large File");
				buf.append("<table style=\"border: none; \">\n");
				String fnam = getFilename(e, key, e.getExpectedMimeType());
				buf.append("<tr><td><b>Filename</b></td><td>");
				buf.append("<a href=\"/"+URLEncoder.encode(key.toString(false))+"\">");
				buf.append(fnam);
				buf.append("</a>");
				buf.append("</td></tr>\n");
				boolean finalized = e.finalizedSize();
				if(e.expectedSize > 0) {
					buf.append("<tr><td><b>");
					if(!finalized)
						buf.append("Expected size (may change)");
					else
						buf.append("Size");
					buf.append("</b></td><td>");
					buf.append(SizeUtil.formatSize(e.expectedSize));
					buf.append("</td></tr>\n");
				}
				String mime = e.getExpectedMimeType();
				if(mime != null) {
					buf.append("<tr><td><b>");
					if(!finalized)
						buf.append("Expected MIME type");
					else
						buf.append("MIME type");
					buf.append("</b></td><td>");
					buf.append(mime);
					buf.append(" bytes </td></tr>\n");
				}
				// FIXME filename
				buf.append("</table>\n");
				buf.append("<br />The Freenet key you requested refers to a large file. Files of this size cannot generally be sent directly to your browser since they take too long for your Freenet node to retrieve. The following options are available: ");
				buf.append("<ul>");
				buf.append("<li><form method=\"get\" action=\"/"+key.toString(false)+"\">");
				buf.append("<input type=\"hidden\" name=\"max-size\" value=\""+e.expectedSize+"\">");
				buf.append("<input type=\"submit\" name=\"fetch\" value=\"Fetch anyway and display file in browser\">");
				buf.append("</form></li>\n");
				buf.append("<li><form method=\"post\" action=\"/queue/\">");
				buf.append("<input type=\"hidden\" name=\"key\" value=\""+key.toString(false)+"\">");
				buf.append("<input type=\"hidden\" name=\"return-type\" value=\"disk\">");
				buf.append("<input type=\"hidden\" name=\"persistence\" value=\"forever\">");
				buf.append("<input type=\"hidden\" name=\"formPassword\" value=\""+node.formPassword+"\">");
				if(mime != null)
					buf.append("<input type=\"hidden\" name=\"type\" value=\""+URLEncoder.encode(mime)+"\">");
				buf.append("<input type=\"submit\" name=\"download\" value=\"Download in background and store in downloads directory\">");
				buf.append("</form></li>\n");
				// FIXME add a queue-a-download option.
//				buf.append("<li>Save it to disk at </li>");
				// FIXME add return-to-referring-page
				//buf.append("<li>Return to the referring page: ");
				buf.append("<li><a href=\"/\" title=\"FProxy Home Page\" >Abort and return to the FProxy home page</a></li>");
				buf.append("</ul>");
				ctx.getPageMaker().makeTail(buf);
				writeReply(ctx, 200, "text/html", "OK", buf.toString());
				// FIXME provide option to queue write to disk.
			} else {
				if(e.errorCodes != null)
					extra = "<pre>"+e.errorCodes.toVerboseString()+"</pre>";
				ctx.getPageMaker().makeHead(buf,FetchException.getShortMessage(e.mode));
				
				buf.append("<div class=\"infobox infobox-error\">\n");
				buf.append("<div class=\"infobox-header\">\n");
				buf.append(FetchException.getShortMessage(e.mode)+"\n");
				buf.append("</div>\n");
				buf.append("<div class=\"infobox-content\">\n");
				
				buf.append("Error: "+HTMLEncoder.encode(msg)+extra+"\n");		
				ctx.getPageMaker().makeBackLink(buf,ctx);
				buf.append("<br><a href=\"/\" title=\"Node Homepage\">Homepage</a>\n");
				buf.append("</div>\n");
				buf.append("</div>\n");
				
				ctx.getPageMaker().makeTail(buf);

				this.writeReply(ctx, 500 /* close enough - FIXME probably should depend on status code */,
						"text/html", FetchException.getShortMessage(e.mode), buf.toString());
			}
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

	public static void maybeCreateFProxyEtc(Node node, Config config) throws IOException, InvalidConfigValueException {
		
		SubConfig fproxyConfig = null;
		try {
			fproxyConfig = new SubConfig("fproxy", config);
		} catch (IllegalArgumentException iae1) {
			fproxyConfig = config.get("fproxy");
		}
		
		try {
			SimpleToadletServer server = new SimpleToadletServer(fproxyConfig, node);
			
			HighLevelSimpleClient client = node.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS);
			
			node.setToadletContainer(server);
			byte[] random = new byte[32];
			node.random.nextBytes(random);
			FProxyToadlet fproxy = new FProxyToadlet(client, random, node);
			node.setFProxy(fproxy);
			server.register(fproxy, "/", false);
			
			PproxyToadlet pproxy = new PproxyToadlet(client, node.pluginManager, node);
			server.register(pproxy, "/plugins/", true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, node, fproxyConfig);
			server.register(welcometoadlet, "/welcome/", true);
			
			PluginToadlet pluginToadlet = new PluginToadlet(client, node.pluginManager2, node);
			server.register(pluginToadlet, "/plugin/", true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config, node);
			server.register(configtoadlet, "/config/", true);
			
			StaticToadlet statictoadlet = new StaticToadlet(client);
			server.register(statictoadlet, "/static/", true);
			
			SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
			server.register(symlinkToadlet, "/sl/", true);
			
			DarknetConnectionsToadlet darknetToadlet = new DarknetConnectionsToadlet(node, client);
			server.register(darknetToadlet, "/darknet/", true);
			
			N2NTMToadlet n2ntmToadlet = new N2NTMToadlet(node, client);
			server.register(n2ntmToadlet, "/send_n2ntm/", true);
			
			QueueToadlet queueToadlet = new QueueToadlet(node, node.getFCPServer(), client);
			server.register(queueToadlet, "/queue/", true);

			// Now start the server.
			server.start();
			
		} catch (IOException ioe) {
			Logger.error(node,"Failed to start FProxy: "+ioe, ioe);
		}
		
		fproxyConfig.finishedInitialization();
	}
	
	/**
	 * Get expected filename for a file.
	 * @param e The FetchException.
	 * @param uri The original URI.
	 * @param expectedMimeType The expected MIME type.
	 */
	private String getFilename(FetchException e, FreenetURI uri, String expectedMimeType) {
		String s = getFilename(e, uri);
		int dotIdx = s.indexOf('.');
		String ext = DefaultMIMETypes.getExtension(expectedMimeType);
		if(ext == null)
			ext = "bin";
		if(dotIdx == -1 && expectedMimeType != null) {
			s += "." + ext;
			return s;
		}
		if(dotIdx != -1) {
			String oldExt = s.substring(dotIdx+1);
			if(DefaultMIMETypes.isValidExt(expectedMimeType, oldExt))
				return s;
			return s + "." + ext;
		}
		return s + "." + ext;
	}
	
	private String getFilename(FetchException e, FreenetURI uri) {
		String fnam = sanitize(uri.getDocName());
		if(fnam != null && fnam.length() > 0) return fnam;
		String[] meta = uri.getAllMetaStrings();
		if(meta != null) {
			for(int i=meta.length-1;i>=0;i++) {
				String s = meta[i];
				if(s == null) continue;
				if(s.length() == 0) continue;
				fnam = sanitize(s);
				if(s != null && s.length() > 0) return fnam;
			}
		}
		return Base64.encode(uri.getRoutingKey());
	}
	
	private String sanitize(String s) {
		if(s == null) return null;
		StringBuffer sb = new StringBuffer(s.length());
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(Character.isLetterOrDigit(c) ||
					c == ' ' || c == '.' || c == '-' || c == '_' ||
					c == '+' || c == ',')
				sb.append(c);
		}
		return sb.toString();
	}
}
