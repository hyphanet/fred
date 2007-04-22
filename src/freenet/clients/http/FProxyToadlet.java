package freenet.clients.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.BindException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.clients.http.filter.ContentFilter.FilterOutput;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.RequestStarter;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SizeUtil;
import freenet.support.URLEncoder;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;

public class FProxyToadlet extends Toadlet {
	
	private static byte[] random;
	final NodeClientCore core;
	
	// ?force= links become invalid after 2 hours.
	private static final long FORCE_GRAIN_INTERVAL = 60*60*1000;
	/** Maximum size for transparent pass-through, should be a config option */
	static final long MAX_LENGTH = 2*1024*1024; // 2MB
	
	static final URI welcome;
	static {
		try {
			welcome = new URI("/welcome/");
		} catch (URISyntaxException e) {
			throw new Error("Broken URI constructor: "+e, e);
		}
	}
	
	public FProxyToadlet(HighLevelSimpleClient client, NodeClientCore core) {
		super(client);
		client.setMaxLength(MAX_LENGTH);
		client.setMaxIntermediateLength(MAX_LENGTH);
		this.core = core;
	}
	
	public String supportedMethods() {
		return "GET";
	}

	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
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

	public static void handleDownload(ToadletContext context, Bucket data, BucketFactory bucketFactory, String mimeType, String requestedMimeType, String forceString, boolean forceDownload, String basePath, FreenetURI key, String extras, String referrer) throws ToadletContextClosedException, IOException {
		if(requestedMimeType != null) {
			if(mimeType == null || !requestedMimeType.equals(mimeType)) {
				if(extras == null) extras = "";
				extras = extras + "&type=" + requestedMimeType;
			}
			mimeType = requestedMimeType;
		}
		
		long now = System.currentTimeMillis();
		boolean force = false;
		if(forceString != null) {
			if(forceString.equals(getForceValue(key, now)) || 
					forceString.equals(getForceValue(key, now-FORCE_GRAIN_INTERVAL)))
				force = true;
		}

		try {
			if((!force) && (!forceDownload)) {
				FilterOutput fo = ContentFilter.filter(data, bucketFactory, mimeType, key.toURI(basePath), null);
				data = fo.data;
				mimeType = fo.type;
				
				if(horribleEvilHack(data) && !(mimeType.startsWith("application/rss+xml"))) {
					HTMLNode pageNode = context.getPageMaker().getPageNode("Potentially Dangerous Content (RSS)", context);
					HTMLNode contentNode = context.getPageMaker().getContentNode(pageNode);
					
					HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
					infobox.addChild("div", "class", "infobox-header", "RSS feed may be dangerous");
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					infoboxContent.addChild("#", "Freenet has detected that the file you are trying to fetch might be RSS. "+
							"RSS cannot be properly filtered by Freenet, and may contain web-bugs (inline images etc which may "+
							"expose your IP address to a malicious site author and therefore break your anonymity). "+
							"Firefox 2.0 and Internet Explorer 7.0 will open the file as RSS even though its content type is \""+HTMLEncoder.encode(mimeType)+"\".");
					infoboxContent.addChild("p", "Your options are:");
					HTMLNode optionList = infoboxContent.addChild("ul");
					HTMLNode option = optionList.addChild("li");
					
					option.addChild("a", "href", basePath + key.toString() + "?type=text/plain&force=" + getForceValue(key, now)+extras, "Click here");
					option.addChild("%", " to open the file as plain text (this <b>may be dangerous</b> if you are running IE7 or FF2).");
					// 	FIXME: is this safe? See bug #131
					option = optionList.addChild("li");
					option.addChild("a", "href", basePath + key.toString() + "?forcedownload"+extras, "Click here");
					option.addChild("%", " to try to force your browser to download the file to disk (<b>this may also be dangerous if you run Firefox 2.0.0 (2.0.1 should fix this)</b>).");
					if(!mimeType.startsWith("text/plain")) {
						option = optionList.addChild("li");
						option.addChild("a", "href", basePath + key.toString() + "?force=" + getForceValue(key, now)+extras, "Click here");
						option.addChild("#", " to open the file as " + mimeType);
						option.addChild("%", " (<b>this may also be dangerous</b>).");
					}
					option = optionList.addChild("li");
					option.addChild("a", "href", basePath + key.toString() + "?type=application/xml+rss&force=" + getForceValue(key, now)+extras, "Click here");
					option.addChild("%", " to open the file as RSS (<b>this is dangerous if the site author is malicious</b>).");
					if(referrer != null) {
						option = optionList.addChild("li");
						option.addChild("a", "href", referrer, "Click here");
						option.addChild("#", " to go back to the referring page.");
					}
					option = optionList.addChild("li");
					option.addChild("a", "href", "/", "Click here");
					option.addChild("#", " to go to the FProxy home page.");
					
					byte[] pageBytes = pageNode.generate().getBytes();
					context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", pageBytes.length);
					context.writeData(pageBytes);
					return;
				}
			}
			
			if (forceDownload) {
				MultiValueTable headers = new MultiValueTable();
				headers.put("Content-Disposition", "attachment; filename=\"" + key.getPreferredFilename() + '"');
				context.sendReplyHeaders(200, "OK", headers, "application/x-msdownload", data.size());
				context.writeData(data);
			} else {
				// Send the data, intact
				context.sendReplyHeaders(200, "OK", new MultiValueTable(), mimeType, data.size());
				context.writeData(data);
			}
		} catch (URISyntaxException use1) {
			/* shouldn't happen */
			use1.printStackTrace();
			Logger.error(FProxyToadlet.class, "could not create URI", use1);
		} catch (UnsafeContentTypeException e) {
			HTMLNode pageNode = context.getPageMaker().getPageNode("Potentially Dangerous Content", context);
			HTMLNode contentNode = context.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
			infobox.addChild("div", "class", "infobox-header", e.getRawTitle());
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			infoboxContent.addChild(e.getHTMLExplanation());
			infoboxContent.addChild("p", "Your options are:");
			HTMLNode optionList = infoboxContent.addChild("ul");
			HTMLNode option = optionList.addChild("li");
			option.addChild("a", "href", basePath + key.toString() + "?type=text/plain"+extras, "Click here");
			option.addChild("#", " to open the file as plain text (this should not be dangerous but it may be garbled).");
			// FIXME: is this safe? See bug #131
			option = optionList.addChild("li");
			option.addChild("a", "href", basePath + key.toString() + "?forcedownload"+extras, "Click here");
			option.addChild("#", " to force your browser to download the file to disk.");
			if(!(mimeType.equals("application/octet-stream") || mimeType.equals("application/x-msdownload"))) {
				option = optionList.addChild("li");
				option.addChild("a", "href", basePath + key.toString() + "?force=" + getForceValue(key, now)+extras, "Click here");
				option.addChild("#", " to open the file as " + mimeType + '.');
			}
			if(referrer != null) {
				option = optionList.addChild("li");
				option.addChild("a", "href", referrer, "Click here");
				option.addChild("#", " to go back to the referring page.");
			}
			option = optionList.addChild("li");
			option.addChild("a", "href", "/", "Click here");
			option.addChild("#", " to go to the FProxy home page.");

			byte[] pageBytes = pageNode.generate().getBytes();
			context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", pageBytes.length);
			context.writeData(pageBytes);
		}
	}
	
	/** Does the first 512 bytes of the data contain anything that Firefox might regard as RSS?
	 * This is a horrible evil hack; we shouldn't be doing blacklisting, we should be doing whitelisting.
	 * REDFLAG Expect future security issues! 
	 * @throws IOException */
	private static boolean horribleEvilHack(Bucket data) throws IOException {
		int sz = (int) Math.min(data.size(), 512);
		if(sz == 0) return false;
		InputStream is = data.getInputStream();
		byte[] buf = new byte[sz];
		// FIXME Fortunately firefox doesn't detect RSS in UTF16 etc ... yet
		is.read(buf);
		/**
		 * Look for any of the following strings:
		 * <rss
		 * &lt;feed
		 * &lt;rdf:RDF
		 * 
		 * If they start at the beginning of the file, or are preceded by one or more &lt;! or &lt;? tags,
		 * then firefox will read it as RSS. In which case we must force it to be downloaded to disk. 
		 */
		if(checkForString(buf, "<rss")) return true;
		if(checkForString(buf, "<feed")) return true;
		if(checkForString(buf, "<rdf:RDF")) return true;
		return false;
	}

	/** Scan for a US-ASCII (byte = char) string within a given buffer of possibly binary data */
	private static boolean checkForString(byte[] buf, String find) {
		int offset = 0;
		int bufProgress = 0;
		while(offset < buf.length) {
			byte b = buf[offset];
			if((int)b == (int)find.charAt(bufProgress)) {
				bufProgress++;
				if(bufProgress == find.length()) return true;
			} else {
				bufProgress = 0;
				if(bufProgress != 0)
					continue; // check if this byte is equal to the first one
			}
			offset++;
		}
		return false;
	}

	public void handleGet(URI uri, HTTPRequest httprequest, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {
		//String ks = uri.toString();
		String ks = uri.getPath();
		
		if (ks.equals("/")) {
			if (httprequest.isParameterSet("key")) {
				MultiValueTable headers = new MultiValueTable();
				
				String k = httprequest.getParam("key");
				FreenetURI newURI;
				try {
					newURI = new FreenetURI(k);
				} catch (MalformedURLException e) {
					Logger.normal(this, "Invalid key: "+e+" for "+k, e);
					sendErrorPage(ctx, 404, "Not found", "Invalid key: "+e);
					return;
				}
				
				headers.put("Location", "/"+newURI);
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			
			RedirectException re = new RedirectException();
			try {
				String querystring = uri.getQuery();
				
				if (querystring == null) {
					re.newuri = welcome;
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
		
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode("Invalid key", ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode errorInfobox = contentNode.addChild("div", "class", "infobox infobox-error");
			errorInfobox.addChild("div", "class", "infobox-header", "Invalid key: "+e);
			HTMLNode errorContent = errorInfobox.addChild("div", "class", "infobox-content");
			errorContent.addChild("#", "Expected a freenet key, but got ");
			errorContent.addChild("code", ks);
			errorContent.addChild("br");
			errorContent.addChild(ctx.getPageMaker().createBackLink(ctx, "Go back"));
			errorContent.addChild("br");
			errorContent.addChild("a", new String[] { "href", "title" }, new String[] { "/", "Node homepage" }, "Homepage");

			this.writeReply(ctx, 400, "text/html", "Invalid key", pageNode.generate());
			return;
		}
		String requestedMimeType = httprequest.getParam("type", null);
		String override = (requestedMimeType == null) ? "" : "?type="+URLEncoder.encode(requestedMimeType);
		try {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "FProxy fetching "+key+" ("+maxSize+ ')');
			FetchResult result = fetch(key, maxSize, httprequest /* fixme replace if HTTPRequest ever becomes comparable */); 
			
			// Now, is it safe?
			
			Bucket data = result.asBucket();
			String mimeType = result.getMimeType();
			
			String referer = sanitizeReferer(ctx);
			
			handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"), "/", key, maxSize != MAX_LENGTH ? "&max-size="+maxSize : "", referer);
			
		} catch (FetchException e) {
			String msg = e.getMessage();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Failed to fetch "+uri+" : "+e);
			if(e.mode == FetchException.NOT_ENOUGH_PATH_COMPONENTS) {
				this.writePermanentRedirect(ctx, "Not enough meta-strings", '/' + key.toString() + '/' + override);
			} else if(e.newURI != null) {
				this.writePermanentRedirect(ctx, msg, '/' +e.newURI.toString() + override);
			} else if(e.mode == FetchException.TOO_BIG) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode("File information", ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", "Large file");
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", "Filename: ");
				option.addChild("a", "href", '/' + key.toString(), getFilename(e, key, e.getExpectedMimeType()));

				boolean finalized = e.finalizedSize();
				if(e.expectedSize > 0) {
					if (finalized) {
						fileInformationList.addChild("li", "Size: " + SizeUtil.formatSize(e.expectedSize));
					} else {
						fileInformationList.addChild("li", "Size: " + SizeUtil.formatSize(e.expectedSize) + " (may change)");
					}
				} else {
					fileInformationList.addChild("li", "Size: unknown");
				}
				String mime = e.getExpectedMimeType();
				if(mime != null) {
					if (finalized) {
						fileInformationList.addChild("li", "MIME type: " + mime);
					} else {
						fileInformationList.addChild("li", "Expected MIME type: " + mime);
					}
				} else {
					fileInformationList.addChild("li", "MIME type: unknown");
				}
				
				infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", "Explanation");
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", "The Freenet key you requested refers to a large file. Files of this size cannot generally be sent directly to your browser since they take too long for your Freenet node to retrieve. The following options are available:");
				HTMLNode optionList = infoboxContent.addChild("ul");
				option = optionList.addChild("li");
				HTMLNode optionForm = option.addChild("form", new String[] { "action", "method" }, new String[] {'/' + key.toString(), "get" });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "max-size", String.valueOf(e.expectedSize == -1 ? Long.MAX_VALUE : e.expectedSize*2) });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "fetch", "Fetch anyway and display file in browser" });
				if(ctx.isAllowedFullAccess()) {
					option = optionList.addChild("li");
					optionForm = ctx.addFormChild(option, "/queue/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					if (mime != null) {
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
					}
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", "Download in background and store in downloads directory" });
				}
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "FProxy home page" }, "Abort and return to the FProxy home page");

				writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			} else {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(FetchException.getShortMessage(e.mode), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", "Error: "+FetchException.getShortMessage(e.mode));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", "Filename: ");
				option.addChild("a", "href", '/' + key.toString(), getFilename(e, key, e.getExpectedMimeType()));

				boolean finalized = e.finalizedSize();
				if(e.expectedSize > 0) {
					if (finalized) {
						fileInformationList.addChild("li", "Size: " + SizeUtil.formatSize(e.expectedSize));
					} else {
						fileInformationList.addChild("li", "Size: " + SizeUtil.formatSize(e.expectedSize) + " (may change)");
					}
				} else {
					fileInformationList.addChild("li", "Size: unknown");
				}
				String mime = e.getExpectedMimeType();
				if(mime != null) {
					if (finalized) {
						fileInformationList.addChild("li", "MIME type: " + mime);
					} else {
						fileInformationList.addChild("li", "Expected MIME type: " + mime);
					}
				} else {
					fileInformationList.addChild("li", "MIME type: unknown");
				}
				
				infobox.addChild("div", "class", "infobox-header", "Explanation");
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("p", "Freenet was unable to retrieve this file. ");
				if(e.isFatal())
					infoboxContent.addChild("p", "This is a fatal error. It is unlikely that retrying will solve the problem.");
				infoboxContent.addChild("p", msg);
				if(e.errorCodes != null) {
					infoboxContent.addChild("p").addChild("pre").addChild("#", e.errorCodes.toVerboseString());
				}
				
				infobox.addChild("div", "class", "infobox-header", "You can:");
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				
				HTMLNode optionList = infoboxContent.addChild("ul");
				if(!e.isFatal() && ctx.isAllowedFullAccess()) {
					option = optionList.addChild("li");
					HTMLNode optionForm = ctx.addFormChild(option, "/queue/", "dnfQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					if (mime != null) {
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
					}
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", "Download it in the background to the downloads directory" });
					
					optionList.addChild("li").
						addChild("a", "href", getLink(key, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"))).addChild("#", "Retry now");
				}
				
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "FProxy home page" }, "Abort and return to the FProxy home page");
				
				option = optionList.addChild("li");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, "Go back to the previous page"));
				option = optionList.addChild("li");
				option.addChild("a", new String[] { "href", "title" }, new String[] { "/", "Node homepage" }, "Return to the node homepage");
				
				this.writeReply(ctx, 500 /* close enough - FIXME probably should depend on status code */,
						"text/html", FetchException.getShortMessage(e.mode), pageNode.generate());
			}
		} catch (SocketException e) {
			// Probably irrelevant
			if(e.getMessage().equals("Broken pipe")) {
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Caught "+e+" while handling GET", e);
			} else {
				Logger.normal(this, "Caught "+e);
			}
			throw e;
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			String msg = "<html><head><title>Internal Error</title></head><body><h1>Internal Error: please report</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + HTMLEncoder.encode(sw.toString()) + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", "Internal Error", msg);
		}
	}

	private String getLink(FreenetURI uri, String requestedMimeType, long maxSize, String force, 
			boolean forceDownload) {
		StringBuffer sb = new StringBuffer();
		sb.append("/");
		sb.append(uri.toACIIString());
		char c = '?';
		if(requestedMimeType != null) {
			sb.append(c).append("type=").append(URLEncoder.encode(requestedMimeType)); c = '&';
		}
		if(maxSize > 0) {
			sb.append(c).append("max-size=").append(maxSize); c = '&';
		}
		if(force != null) {
			sb.append(c).append("force=").append(force); c = '&';
		}
		if(forceDownload) {
			sb.append(c).append("forcedownload=true"); c = '&';
		}
		return sb.toString();
	}

	private String sanitizeReferer(ToadletContext ctx) {
		// FIXME we do something similar in the GenericFilterCallback thingy?
		String referer = (String) ctx.getHeaders().get("referer");
		if(referer != null) {
			try {
				URI refererURI = new URI(referer);
				String path = refererURI.getPath();
				while(path.startsWith("/")) path = path.substring(1);
				FreenetURI furi = new FreenetURI(path);
				HTTPRequest req = new HTTPRequestImpl(refererURI);
				String type = req.getParam("type");
				referer = "/" + furi.toString();
				if(type != null && type.length() > 0)
					referer += "?type=" + type;
			} catch (MalformedURLException e) {
				referer = "/";
				Logger.normal(this, "Caught MalformedURLException on the referer : "+e.getMessage());
			} catch (Throwable t) {
				Logger.error(this, "Caught handling referrer: "+t+" for "+referer, t);
				referer = null;
			}
		}
		return referer;
	}

	private static String getForceValue(FreenetURI key, long time) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
		try{
			bos.write(random);
			bos.write(key.toString().getBytes("UTF-8"));
			bos.write(Long.toString(time / FORCE_GRAIN_INTERVAL).getBytes());
		} catch (IOException e) {
			throw new Error(e);
		}
		
		String f = HexUtil.bytesToHex(SHA256.digest(bos.toByteArray()));
		return f;
	}

	public static void maybeCreateFProxyEtc(NodeClientCore core, Node node, Config config, SubConfig fproxyConfig) throws IOException, InvalidConfigValueException {
		
		try {
			
			SimpleToadletServer server = new SimpleToadletServer(fproxyConfig, core);
			
			HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
			
			core.setToadletContainer(server);
			random = new byte[32];
			core.random.nextBytes(random);
			FProxyToadlet fproxy = new FProxyToadlet(client, core);
			core.setFProxy(fproxy);
			server.register(fproxy, "/", false, "Home", "homepage", false);
			
			PproxyToadlet pproxy = new PproxyToadlet(client, node.pluginManager, core);
			server.register(pproxy, "/plugins/", true, "Plugins", "configure and manage plugins", true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, node);
			server.register(welcometoadlet, "/welcome/", true, false);
			
			PluginToadlet pluginToadlet = new PluginToadlet(client, node.pluginManager2, core);
			server.register(pluginToadlet, "/plugin/", true, true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config, node, core);
			server.register(configtoadlet, "/config/", true, "Configuration", "configure your node", true);
			
			StaticToadlet statictoadlet = new StaticToadlet(client);
			server.register(statictoadlet, "/static/", true, false);
			
			SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
			server.register(symlinkToadlet, "/sl/", true, false);
			
			DarknetConnectionsToadlet darknetToadlet = new DarknetConnectionsToadlet(node, core, client);
			server.register(darknetToadlet, "/darknet/", true, "Friends", "manage f2f connections", true);
			
			N2NTMToadlet n2ntmToadlet = new N2NTMToadlet(node, core, client);
			server.register(n2ntmToadlet, "/send_n2ntm/", true, true);
			
			QueueToadlet queueToadlet = new QueueToadlet(core, core.getFCPServer(), client);
			server.register(queueToadlet, "/queue/", true, "Queue", "manage queued requests", false);
			
			StatisticsToadlet statisticsToadlet = new StatisticsToadlet(node, core, client);
			server.register(statisticsToadlet, "/stats/", true, "Statistics", "view statistics", true);
			
			LocalFileInsertToadlet localFileInsertToadlet = new LocalFileInsertToadlet(core, client);
			server.register(localFileInsertToadlet, "/files/", true, false);

			BrowserTestToadlet browsertTestToadlet = new BrowserTestToadlet(client, core);
			server.register(browsertTestToadlet, "/test/", true, false);
			
			TranslationToadlet translationToadlet = new TranslationToadlet(client, core);
			server.register(translationToadlet, TranslationToadlet.TOADLET_URL, true, "Translation", "helper" +
					" to translate the node's interface into your native language", true);
			
			// Now start the server.
			server.start();
			
		}catch (BindException e){
			Logger.error(core,"Failed to start FProxy port already bound: isn't freenet already running ?");
			System.err.println("Failed to start FProxy port already bound: isn't freenet already running ?");
			throw new InvalidConfigValueException("Can't bind fproxy on that port!");
		}catch (IOException ioe) {
			Logger.error(core,"Failed to start FProxy: "+ioe, ioe);
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
		String s = uri.getPreferredFilename();
		int dotIdx = s.lastIndexOf('.');
		String ext = DefaultMIMETypes.getExtension(expectedMimeType);
		if(ext == null)
			ext = "bin";
		if((dotIdx == -1) && (expectedMimeType != null)) {
			s += '.' + ext;
			return s;
		}
		if(dotIdx != -1) {
			String oldExt = s.substring(dotIdx+1);
			if(DefaultMIMETypes.isValidExt(expectedMimeType, oldExt))
				return s;
			return s + '.' + ext;
		}
		return s + '.' + ext;
	}
	
}
