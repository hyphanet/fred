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
import freenet.l10n.L10n;
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
					HTMLNode pageNode = context.getPageMaker().getPageNode(l10n("dangerousRSSTitle"), context);
					HTMLNode contentNode = context.getPageMaker().getContentNode(pageNode);
					
					HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
					infobox.addChild("div", "class", "infobox-header", l10n("dangerousRSSSubtitle"));
					HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
					infoboxContent.addChild("#", L10n.getString("FProxyToadlet.dangerousRSS", new String[] { "type" }, new String[] { mimeType }));
					infoboxContent.addChild("p", l10n("options"));
					HTMLNode optionList = infoboxContent.addChild("ul");
					HTMLNode option = optionList.addChild("li");
					
					L10n.addL10nSubstitution(option, "openPossRSSAsPlainText", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+HTMLEncoder.encode(basePath+key.toString()+"?type=text/plain&force="+getForceValue(key,now)+extras)+"\">",
								"</a>",
								"<b>",
								"</b>" });
					// 	FIXME: is this safe? See bug #131
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "openPossRSSForceDisk", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+HTMLEncoder.encode(basePath+key.toString()+"?forcedownload"+extras)+"\">",
								"</a>",
								"<b>",
								"</b>" });
					boolean mimeRSS = mimeType.startsWith("application/xml+rss") || mimeType.startsWith("text/xml"); /* blergh! */
					if(!(mimeRSS || mimeType.startsWith("text/plain"))) {
						option = optionList.addChild("li");
						L10n.addL10nSubstitution(option, "openRSSForce", new String[] { "link", "/link", "bold", "/bold", "mime" },
								new String[] { 
									"<a href=\""+HTMLEncoder.encode(basePath+key.toString()+"?force="+getForceValue(key, now)+extras)+"\">",
									"</a>",
									"<b>",
									"</b>",
									HTMLEncoder.encode(mimeType) /* these are not encoded because mostly they are tags, so we have to encode it */ });
					}
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "openRSSAsRSS", new String[] { "link", "/link", "bold", "/bold" },
							new String[] {
								"<a href=\""+HTMLEncoder.encode(basePath + key.toString() + "?type=application/xml+rss&force=" + getForceValue(key, now)+extras)+"\">",
								"</a>",
								"<b>",
								"</b>" });
					if(referrer != null) {
						option = optionList.addChild("li");
						L10n.addL10nSubstitution(option, "backToReferrer", new String[] { "link", "/link" },
								new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
					}
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "backToFProxy", new String[] { "link", "/link" },
							new String[] { "<a href=\"/\">", "</a>" });
					
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
			HTMLNode pageNode = context.getPageMaker().getPageNode(l10n("dangerousContentTitle"), context);
			HTMLNode contentNode = context.getPageMaker().getContentNode(pageNode);
			
			HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-alert");
			infobox.addChild("div", "class", "infobox-header", e.getRawTitle());
			HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
			infoboxContent.addChild(e.getHTMLExplanation());
			infoboxContent.addChild("p", l10n("options"));
			HTMLNode optionList = infoboxContent.addChild("ul");
			HTMLNode option = optionList.addChild("li");
			L10n.addL10nSubstitution(option, "openAsText", new String[] { "link", "/link" }, new String[] { "<a href=\""+HTMLEncoder.encode(basePath+key.toString()+"?type=text/plain"+extras+"\">"), "</a>" });
			// FIXME: is this safe? See bug #131
			option = optionList.addChild("li");
			L10n.addL10nSubstitution(option, "openAsText", new String[] { "link", "/link" }, new String[] { "<a href=\""+HTMLEncoder.encode(basePath+key.toString()+"?forcedownload"+extras+"\">"), "</a>" });
			if(!(mimeType.equals("application/octet-stream") || mimeType.equals("application/x-msdownload"))) {
				option = optionList.addChild("li");
				
				L10n.addL10nSubstitution(option, "openForce", new String[] { "link", "/link", "mime" }, new String[] { "<a href=\""+HTMLEncoder.encode(basePath + key.toString() + "?force=" + getForceValue(key, now)+extras)+"\">", "</a>", HTMLEncoder.encode(mimeType)});
			}
			if(referrer != null) {
				option = optionList.addChild("li");
				L10n.addL10nSubstitution(option, "backToReferrer", new String[] { "link", "/link" },
						new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
			}
			option = optionList.addChild("li");
			L10n.addL10nSubstitution(option, "backToFProxy", new String[] { "link", "/link" },
					new String[] { "<a href=\"/\">", "</a>" });

			byte[] pageBytes = pageNode.generate().getBytes();
			context.sendReplyHeaders(200, "OK", new MultiValueTable(), "text/html; charset=utf-8", pageBytes.length);
			context.writeData(pageBytes);
		}
	}
	
	private static String l10n(String msg) {
		return L10n.getString("FProxyToadlet."+msg);
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
					sendErrorPage(ctx, 404, l10n("notFoundTitle"), L10n.getString("invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
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
				this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
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
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("invalidKey"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode errorInfobox = contentNode.addChild("div", "class", "infobox infobox-error");
			errorInfobox.addChild("div", "class", "infobox-header", L10n.getString("invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
			HTMLNode errorContent = errorInfobox.addChild("div", "class", "infobox-content");
			errorContent.addChild("#", l10n("expectedKeyButGot"));
			errorContent.addChild("code", ks);
			errorContent.addChild("br");
			errorContent.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBack")));
			errorContent.addChild("br");
			errorContent.addChild("a", new String[] { "href", "title" }, new String[] { "/", l10n("homepageTitle") }, l10n("homepage"));

			this.writeReply(ctx, 400, "text/html", l10n("invalidKeyTitle"), pageNode.generate());
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
				this.writePermanentRedirect(ctx, l10n("notEnoughMetaStrings"), '/' + key.toString() + '/' + override);
			} else if(e.newURI != null) {
				this.writePermanentRedirect(ctx, msg, '/' +e.newURI.toString() + override);
			} else if(e.mode == FetchException.TOO_BIG) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("fileInformationTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("largeFile"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", l10n("filenameLabel"));
				option.addChild("a", "href", '/' + key.toString(), getFilename(e, key, e.getExpectedMimeType()));

				String mime = writeSizeAndMIME(fileInformationList, e);
				
				infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("explanationTitle"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("largeFileExplanationAndOptions"));
				HTMLNode optionList = infoboxContent.addChild("ul");
				option = optionList.addChild("li");
				HTMLNode optionForm = option.addChild("form", new String[] { "action", "method" }, new String[] {'/' + key.toString(), "get" });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "max-size", String.valueOf(e.expectedSize == -1 ? Long.MAX_VALUE : e.expectedSize*2) });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "fetch", l10n("fetchLargeFileAnywayAndDisplay") });
				if(ctx.isAllowedFullAccess()) {
					option = optionList.addChild("li");
					optionForm = ctx.addFormChild(option, "/queue/", "tooBigQueueForm");
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
					if (mime != null) {
						optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mime });
					}
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDisk") });
				}
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", "FProxy home page" }, l10n("abortToHomepage"));

				writeReply(ctx, 200, "text/html", "OK", pageNode.generate());
			} else {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(FetchException.getShortMessage(e.mode), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", l10n("errorWithReason", "error", FetchException.getShortMessage(e.mode)));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", l10n("filenameLabel"));
				option.addChild("a", "href", '/' + key.toString(), getFilename(e, key, e.getExpectedMimeType()));

				String mime = writeSizeAndMIME(fileInformationList, e);
				infobox.addChild("div", "class", "infobox-header", l10n("explanationTitle"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("p", l10n("unableToRetrieve"));
				if(e.isFatal())
					infoboxContent.addChild("p", l10n("errorIsFatal"));
				infoboxContent.addChild("p", msg);
				if(e.errorCodes != null) {
					infoboxContent.addChild("p").addChild("pre").addChild("#", e.errorCodes.toVerboseString());
				}
				
				infobox.addChild("div", "class", "infobox-header", l10n("options"));
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
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDisk")});
					
					optionList.addChild("li").
						addChild("a", "href", getLink(key, requestedMimeType, maxSize, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"))).addChild("#", l10n("retryNow"));
				}
				
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", l10n("homepageTitle") }, l10n("abortToHomepage"));
				
				option = optionList.addChild("li");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
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
			String msg = "<html><head><title>"+l10n("internalErrorTitle")+"</title></head><body><h1>"+l10n("internalErrorReportIt")+"</h1><pre>";
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			t.printStackTrace(pw);
			pw.flush();
			msg = msg + HTMLEncoder.encode(sw.toString()) + "</pre></body></html>";
			this.writeReply(ctx, 500, "text/html", l10n("internalErrorTitle"), msg);
		}
	}

	private String writeSizeAndMIME(HTMLNode fileInformationList, FetchException e) {
		boolean finalized = e.finalizedSize();
		if(e.expectedSize > 0) {
			if (finalized) {
				fileInformationList.addChild("li", l10n("sizeLabel") + SizeUtil.formatSize(e.expectedSize));
			} else {
				fileInformationList.addChild("li", l10n("sizeLabel") + SizeUtil.formatSize(e.expectedSize) + l10n("mayChange"));
			}
		} else {
			fileInformationList.addChild("li", l10n("sizeUnknown"));
		}
		String mime = e.getExpectedMimeType();
		if(mime != null) {
			fileInformationList.addChild("li", L10n.getString("FProxyToadlet."+(finalized ? "mimeType" : "expectedMimeType"), new String[] { "mime" }, new String[] { mime }));;
		} else {
			fileInformationList.addChild("li", l10n("unknownMIMEType"));
		}
		return mime;
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("FProxyToadlet."+key, new String[] { pattern }, new String[] { value });
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
		
		// FIXME how to change these on the fly when the interface language is changed?
		
		try {
			
			SimpleToadletServer server = new SimpleToadletServer(fproxyConfig, core);
			
			HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
			
			core.setToadletContainer(server);
			random = new byte[32];
			core.random.nextBytes(random);
			FProxyToadlet fproxy = new FProxyToadlet(client, core);
			core.setFProxy(fproxy);
			server.register(fproxy, "/", false, l10n("welcomeTitle"), l10n("welcome"), false);
			
			PproxyToadlet pproxy = new PproxyToadlet(client, node.pluginManager, core);
			server.register(pproxy, "/plugins/", true, l10n("pluginsTitle"), l10n("plugins"), true);
			
			WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, node);
			server.register(welcometoadlet, "/welcome/", true, false);
			
			PluginToadlet pluginToadlet = new PluginToadlet(client, node.pluginManager2, core);
			server.register(pluginToadlet, "/plugin/", true, true);
			
			ConfigToadlet configtoadlet = new ConfigToadlet(client, config, node, core);
			server.register(configtoadlet, "/config/", true, l10n("configTitle"), l10n("config"), true);
			
			StaticToadlet statictoadlet = new StaticToadlet(client);
			server.register(statictoadlet, "/static/", true, false);
			
			SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
			server.register(symlinkToadlet, "/sl/", true, false);
			
			DarknetConnectionsToadlet darknetToadlet = new DarknetConnectionsToadlet(node, core, client);
			server.register(darknetToadlet, "/darknet/", true, l10n("friendsTitle"), l10n("friends"), true);
			
			N2NTMToadlet n2ntmToadlet = new N2NTMToadlet(node, core, client);
			server.register(n2ntmToadlet, "/send_n2ntm/", true, true);
			
			QueueToadlet queueToadlet = new QueueToadlet(core, core.getFCPServer(), client);
			server.register(queueToadlet, "/queue/", true, l10n("queueTitle"), l10n("queue"), false);
			
			StatisticsToadlet statisticsToadlet = new StatisticsToadlet(node, core, client);
			server.register(statisticsToadlet, "/stats/", true, l10n("statsTitle"), l10n("stats"), true);
			
			LocalFileInsertToadlet localFileInsertToadlet = new LocalFileInsertToadlet(core, client);
			server.register(localFileInsertToadlet, "/files/", true, false);
			
			BookmarkEditorToadlet bookmarkEditorToadlet = new BookmarkEditorToadlet(client, core);
			server.register(bookmarkEditorToadlet, "/bookmarkEditor/", true, false);

			BrowserTestToadlet browsertTestToadlet = new BrowserTestToadlet(client, core);
			server.register(browsertTestToadlet, "/test/", true, false);
			
			TranslationToadlet translationToadlet = new TranslationToadlet(client, core);
			server.register(translationToadlet, TranslationToadlet.TOADLET_URL, true, l10n("translationTitle"), l10n("translation"), true);
			
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
