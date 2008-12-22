package freenet.clients.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

import freenet.client.DefaultMIMETypes;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.bookmark.BookmarkManager;
import freenet.clients.http.filter.ContentFilter;
import freenet.clients.http.filter.FoundURICallback;
import freenet.clients.http.filter.UnsafeContentTypeException;
import freenet.clients.http.filter.ContentFilter.FilterOutput;
import freenet.config.Config;
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
import freenet.support.io.Closer;

public final class FProxyToadlet extends Toadlet {
	
	private static byte[] random;
	final NodeClientCore core;
	
	private static FoundURICallback prefetchHook;
	static final Set<String> prefetchAllowedTypes = new HashSet<String>();
	static {
		// Only valid inlines
		prefetchAllowedTypes.add("image/png");
		prefetchAllowedTypes.add("image/jpeg");
		prefetchAllowedTypes.add("image/gif");
	}
	
	// ?force= links become invalid after 2 hours.
	private static final long FORCE_GRAIN_INTERVAL = 60*60*1000;
	/** Maximum size for transparent pass-through, should be a config option */
	static long MAX_LENGTH = 2*1024*1024; // 2MB
	
	static final URI welcome;
	static {
		try {
			welcome = new URI("/welcome/");
		} catch (URISyntaxException e) {
			throw new Error("Broken URI constructor: "+e, e);
		}
	}
	
	public FProxyToadlet(final HighLevelSimpleClient client, NodeClientCore core) {
		super(client);
		client.setMaxLength(MAX_LENGTH);
		client.setMaxIntermediateLength(MAX_LENGTH);
		this.core = core;
		prefetchHook = new FoundURICallback() {

				public void foundURI(FreenetURI uri) {
					// Ignore
				}
				
				public void foundURI(FreenetURI uri, boolean inline) {
					if(!inline) return;
					if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "Prefetching "+uri);
					client.prefetch(uri, 60*1000, 512*1024, prefetchAllowedTypes);
				}

				public void onText(String text, String type, URI baseURI) {
					// Ignore
				}
				
			};
	}
	
	@Override
	public String supportedMethods() {
		return "GET";
	}

	@Override
	public void handlePost(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String ks = uri.getPath();
		
		if (ks.equals("/")||ks.startsWith("/servlet/")) {
			try {
	            throw new RedirectException("/welcome/");
			} catch (URISyntaxException e) {
				// HUH!?!
			}
		}		
	}

	public static void handleDownload(ToadletContext context, Bucket data, BucketFactory bucketFactory, String mimeType, String requestedMimeType, String forceString, boolean forceDownload, String basePath, FreenetURI key, String extras, String referrer, boolean downloadLink, ToadletContext ctx, NodeClientCore core) throws ToadletContextClosedException, IOException {
		ToadletContainer container = context.getContainer();
		if(Logger.shouldLog(Logger.MINOR, FProxyToadlet.class))
			Logger.minor(FProxyToadlet.class, "handleDownload(data.size="+data.size()+", mimeType="+mimeType+", requestedMimeType="+requestedMimeType+", forceDownload="+forceDownload+", basePath="+basePath+", key="+key);
		String extrasNoMime = extras; // extras will not include MIME type to start with - REDFLAG maybe it should be an array
		if(requestedMimeType != null) {
			if(mimeType == null || !requestedMimeType.equals(mimeType)) {
				if(extras == null) extras = "";
				extras = extras + "&type=" + requestedMimeType;
			}
			mimeType = requestedMimeType;
		}
		long size = data.size();
		
		long now = System.currentTimeMillis();
		boolean force = false;
		if(forceString != null) {
			if(forceString.equals(getForceValue(key, now)) || 
					forceString.equals(getForceValue(key, now-FORCE_GRAIN_INTERVAL)))
				force = true;
		}

		Bucket toFree = null;
		try {
			if((!force) && (!forceDownload)) {
				FilterOutput fo = ContentFilter.filter(data, bucketFactory, mimeType, key.toURI(basePath), container.enableInlinePrefetch() ? prefetchHook : null);
				if(data != fo.data) toFree = fo.data;
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
					
					L10n.addL10nSubstitution(option, "FProxyToadlet.openPossRSSAsPlainText", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+basePath+key.toString()+"?type=text/plain&force="+getForceValue(key,now)+extrasNoMime+"\">",
								"</a>",
								"<b>",
								"</b>" });
					// 	FIXME: is this safe? See bug #131
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "FProxyToadlet.openPossRSSForceDisk", new String[] { "link", "/link", "bold", "/bold" },
							new String[] { 
								"<a href=\""+basePath+key.toString()+"?forcedownload"+extras+"\">",
								"</a>",
								"<b>",
								"</b>" });
					boolean mimeRSS = mimeType.startsWith("application/xml+rss") || mimeType.startsWith("text/xml"); /* blergh! */
					if(!(mimeRSS || mimeType.startsWith("text/plain"))) {
						option = optionList.addChild("li");
						L10n.addL10nSubstitution(option, "FProxyToadlet.openRSSForce", new String[] { "link", "/link", "bold", "/bold", "mime" },
								new String[] { 
									"<a href=\""+basePath+key.toString()+"?force="+getForceValue(key, now)+extras+"\">",
									"</a>",
									"<b>",
									"</b>",
									HTMLEncoder.encode(mimeType) /* these are not encoded because mostly they are tags, so we have to encode it */ });
					}
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "FProxyToadlet.openRSSAsRSS", new String[] { "link", "/link", "bold", "/bold" },
							new String[] {
								"<a href=\""+basePath + key.toString() + "?type=application/xml+rss&force=" + getForceValue(key, now)+extrasNoMime+"\">",
								"</a>",
								"<b>",
								"</b>" });
					if(referrer != null) {
						option = optionList.addChild("li");
						L10n.addL10nSubstitution(option, "FProxyToadlet.backToReferrer", new String[] { "link", "/link" },
								new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
					}
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "FProxyToadlet.backToFProxy", new String[] { "link", "/link" },
							new String[] { "<a href=\"/\">", "</a>" });
					
					byte[] pageBytes = pageNode.generate().getBytes("UTF-8");
					context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), "text/html; charset=utf-8", pageBytes.length);
					context.writeData(pageBytes);
					return;
				}
			}
			
			if (forceDownload) {
				MultiValueTable<String, String> headers = new MultiValueTable<String, String>();
				headers.put("Content-Disposition", "attachment; filename=\"" + key.getPreferredFilename() + '"');
				context.sendReplyHeaders(200, "OK", headers, "application/x-msdownload", data.size());
				context.writeData(data);
			} else {
				// Send the data, intact
				context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), mimeType, data.size());
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
			HTMLNode list = infoboxContent.addChild("ul");
			writeSizeAndMIME(list, size, mimeType, true);
			infoboxContent.addChild("p").addChild(e.getHTMLExplanation());
			infoboxContent.addChild("p", l10n("options"));
			HTMLNode optionList = infoboxContent.addChild("ul");
			HTMLNode option;
			
			if((mimeType.equals("application/x-freenet-index")) && (core.node.pluginManager.isPluginLoaded("plugins.ThawIndexBrowser.ThawIndexBrowser"))) {
				option = optionList.addChild("li");
				L10n.addL10nSubstitution(option, "FProxyToadlet.openAsThawIndex", new String[] { "link", "/link" }, new String[] { "<b><a href=\""+basePath + "plugins/plugins.ThawIndexBrowser.ThawIndexBrowser/?key=" + key.toString() + "\">", "</a></b>" });
			}
			
			option = optionList.addChild("li");
			// FIXME: is this safe? See bug #131
			L10n.addL10nSubstitution(option, "FProxyToadlet.openAsText", new String[] { "link", "/link" }, new String[] { "<a href=\""+basePath+key.toString()+"?type=text/plain"+extrasNoMime+"\">", "</a>" });

			option = optionList.addChild("li");
			L10n.addL10nSubstitution(option, "FProxyToadlet.openForceDisk", new String[] { "link", "/link" }, new String[] { "<a href=\""+basePath+key.toString()+"?forcedownload"+extras+"\">", "</a>" });
			if(!(mimeType.equals("application/octet-stream") || mimeType.equals("application/x-msdownload"))) {
				option = optionList.addChild("li");
				L10n.addL10nSubstitution(option, "FProxyToadlet.openForce", new String[] { "link", "/link", "mime" }, new String[] { "<a href=\""+basePath + key.toString() + "?force=" + getForceValue(key, now)+extras+"\">", "</a>", HTMLEncoder.encode(mimeType)});
			}
			if(referrer != null) {
				option = optionList.addChild("li");
				L10n.addL10nSubstitution(option, "FProxyToadlet.backToReferrer", new String[] { "link", "/link" },
						new String[] { "<a href=\""+HTMLEncoder.encode(referrer)+"\">", "</a>" });
			}
			option = optionList.addChild("li");
			L10n.addL10nSubstitution(option, "FProxyToadlet.backToFProxy", new String[] { "link", "/link" },
					new String[] { "<a href=\"/\">", "</a>" });
			if(ctx.isAllowedFullAccess() || !container.publicGatewayMode()) {
				option = optionList.addChild("li");
				HTMLNode optionForm = ctx.addFormChild(option, "/queue/", "tooBigQueueForm");
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "key", key.toString() });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "return-type", "disk" });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "persistence", "forever" });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "type", mimeType });
				optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "download", l10n("downloadInBackgroundToDisk") });
			}

			byte[] pageBytes = pageNode.generate().getBytes("UTF-8");
			context.sendReplyHeaders(200, "OK", new MultiValueTable<String, String>(), "text/html; charset=utf-8", pageBytes.length);
			context.writeData(pageBytes);
		} finally {
			if(toFree != null) toFree.free();
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
		InputStream is = null;
		try {
			int sz = (int) Math.min(data.size(), 512);
			if(sz == 0)
				return false;
			is = data.getInputStream();
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
			if(checkForString(buf, "<rss"))
				return true;
			if(checkForString(buf, "<feed"))
				return true;
			if(checkForString(buf, "<rdf:RDF"))
				return true;
		}
		finally {
			Closer.close(is);
		}
		return false;
	}

	/** Scan for a US-ASCII (byte = char) string within a given buffer of possibly binary data */
	private static boolean checkForString(byte[] buf, String find) {
		int offset = 0;
		int bufProgress = 0;
		while(offset < buf.length) {
			byte b = buf[offset];
			if(b == find.charAt(bufProgress)) {
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

	@Override
	public void handleGet(URI uri, HTTPRequest httprequest, ToadletContext ctx) 
			throws ToadletContextClosedException, IOException, RedirectException {

		String ks = uri.getPath();
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		if (ks.equals("/")) {
			if (httprequest.isParameterSet("key")) {
				String k = httprequest.getParam("key");
				FreenetURI newURI;
				try {
					newURI = new FreenetURI(k);
				} catch (MalformedURLException e) {
					Logger.normal(this, "Invalid key: "+e+" for "+k, e);
					sendErrorPage(ctx, 404, l10n("notFoundTitle"), L10n.getString("FProxyToadlet.invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
					return;
				}
				
				if(logMINOR) Logger.minor(this, "Redirecting to FreenetURI: "+newURI);
				String type = httprequest.getParam("type");
				String location;
				if ((type != null) && (type.length() > 0)) {
					location =  "/"+newURI + "?type=" + type;
				} else {
					location =  "/"+newURI;
				}
				writeTemporaryRedirect(ctx, null, location);
				return;
			}
			
			try {
				String querystring = uri.getQuery();
				
				if (querystring == null) {
					throw new RedirectException(welcome);
				} else {
					// TODP possibly a proper URLEncode method
					querystring = querystring.replace(' ', '+');
					throw new RedirectException("/welcome/?" + querystring);
				}
			} catch (URISyntaxException e) {
				// HUH!?!
			}
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
		}else if(ks.equals("/robots.txt") && ctx.doRobots()){
			this.writeTextReply(ctx, 200, "Ok", "User-agent: *\nDisallow: /");
			return;
		}else if(ks.startsWith("/darknet/") || ks.equals("/darknet")) { //TODO (pre-build 1045 url format) remove when obsolete
			writePermanentRedirect(ctx, "obsoleted", "/friends/");
			return;
		}else if(ks.startsWith("/opennet/") || ks.equals("/opennet")) { //TODO (pre-build 1045 url format) remove when obsolete
			writePermanentRedirect(ctx, "obsoleted", "/strangers/");
			return;
		}
		
		if(ks.startsWith("/"))
			ks = ks.substring(1);
		
		long maxSize;
		
		boolean restricted = (container.publicGatewayMode() && !ctx.isAllowedFullAccess());
		
		if(restricted)
			maxSize = MAX_LENGTH;
		else 
			maxSize = httprequest.getLongParam("max-size", MAX_LENGTH);
		
		FreenetURI key;
		try {
			key = new FreenetURI(ks);
		} catch (MalformedURLException e) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("invalidKeyTitle"), ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

			HTMLNode errorInfobox = contentNode.addChild("div", "class", "infobox infobox-error");
			errorInfobox.addChild("div", "class", "infobox-header", L10n.getString("FProxyToadlet.invalidKeyWithReason", new String[] { "reason" }, new String[] { e.toString() }));
			HTMLNode errorContent = errorInfobox.addChild("div", "class", "infobox-content");
			errorContent.addChild("#", l10n("expectedKeyButGot"));
			errorContent.addChild("code", ks);
			errorContent.addChild("br");
			errorContent.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBack")));
			errorContent.addChild("br");
			addHomepageLink(errorContent);

			this.writeHTMLReply(ctx, 400, l10n("invalidKeyTitle"), pageNode.generate());
			return;
		}
		String requestedMimeType = httprequest.getParam("type", null);
		String override = (requestedMimeType == null) ? "" : "?type="+URLEncoder.encode(requestedMimeType,true);
		// No point passing ?force= across a redirect, since the key will change.
		// However, there is every point in passing ?forcedownload.
		if(httprequest.isParameterSet("forcedownload")) {
			if(override.length() == 0) override = "?forcedownload";
			else override = override+"&forcedownload";
		}
		Bucket data = null;
		try {
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "FProxy fetching "+key+" ("+maxSize+ ')');
			FetchResult result = fetch(key, maxSize, httprequest /* fixme replace if HTTPRequest ever becomes comparable */); 
			
			// Now, is it safe?
			
			data = result.asBucket();
			String mimeType = result.getMimeType();
			
			String referer = sanitizeReferer(ctx);
			
			
			handleDownload(ctx, data, ctx.getBucketFactory(), mimeType, requestedMimeType, httprequest.getParam("force", null), httprequest.isParameterSet("forcedownload"), "/", key, maxSize != MAX_LENGTH ? "&max-size="+SizeUtil.formatSizeWithoutSpace(maxSize) : "", referer, true, ctx, core);
			
		} catch (FetchException e) {
			String msg = e.getMessage();
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Failed to fetch "+uri+" : "+e);
			if(e.newURI != null) {
				Toadlet.writePermanentRedirect(ctx, msg, '/' +e.newURI.toASCIIString() + override);
			} else if(e.mode == FetchException.TOO_BIG) {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("fileInformationTitle"), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
				
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("largeFile"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", (l10n("filenameLabel") + ' '));
				option.addChild("a", "href", '/' + key.toString(), getFilename(e, key, e.getExpectedMimeType()));

				String mime = writeSizeAndMIME(fileInformationList, e);
				
				infobox = contentNode.addChild("div", "class", "infobox infobox-information");
				infobox.addChild("div", "class", "infobox-header", l10n("explanationTitle"));
				infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("largeFileExplanationAndOptions"));
				HTMLNode optionList = infoboxContent.addChild("ul");
				if(!restricted) {
					option = optionList.addChild("li");
					HTMLNode optionForm = option.addChild("form", new String[] { "action", "method" }, new String[] {'/' + key.toString(), "get" });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "max-size", String.valueOf(e.expectedSize == -1 ? Long.MAX_VALUE : e.expectedSize*2) });
					optionForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "fetch", l10n("fetchLargeFileAnywayAndDisplay") });
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

				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", L10n.getString("Toadlet.homepage") }, l10n("abortToHomepage"));
				
				option = optionList.addChild("li");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			} else {
				HTMLNode pageNode = ctx.getPageMaker().getPageNode(FetchException.getShortMessage(e.mode), ctx);
				HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);

				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-error");
				infobox.addChild("div", "class", "infobox-header", l10n("errorWithReason", "error", FetchException.getShortMessage(e.mode)));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				HTMLNode fileInformationList = infoboxContent.addChild("ul");
				HTMLNode option = fileInformationList.addChild("li");
				option.addChild("#", (l10n("filenameLabel") + ' '));
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
				
				if((e.mode == FetchException.NOT_IN_ARCHIVE) && (core.node.pluginManager.isPluginLoaded("plugins.KeyExplorer.KeyExplorer"))) {
					option = optionList.addChild("li");
					L10n.addL10nSubstitution(option, "FProxyToadlet.openWithKeyExplorer", new String[] { "link", "/link" }, new String[] { "<a href=\"/plugins/plugins.KeyExplorer.KeyExplorer/?key=" + key.toString() + "\">", "</a>" });
				}
				
				if(!e.isFatal() && (ctx.isAllowedFullAccess() || !container.publicGatewayMode())) {
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
				
				optionList.addChild("li").addChild("a", new String[] { "href", "title" }, new String[] { "/", L10n.getString("Toadlet.homepage") }, l10n("abortToHomepage"));
				
				option = optionList.addChild("li");
				option.addChild(ctx.getPageMaker().createBackLink(ctx, l10n("goBackToPrev")));
				
				this.writeHTMLReply(ctx, (e.mode == 10) ? 404 : 500 /* close enough - FIXME probably should depend on status code */,
						"Internal Error", pageNode.generate());
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
			writeInternalError(t, ctx);
		} finally {
			if(data != null) data.free();
		}
	}

	private static String writeSizeAndMIME(HTMLNode fileInformationList, FetchException e) {
		boolean finalized = e.finalizedSize();
		String mime = e.getExpectedMimeType();
		long size = e.expectedSize;
		writeSizeAndMIME(fileInformationList, size, mime, finalized);
		return mime;
	}

	private static void writeSizeAndMIME(HTMLNode fileInformationList, long size, String mime, boolean finalized) {
		if(size > 0) {
			if (finalized) {
				fileInformationList.addChild("li", (l10n("sizeLabel") + ' ') + SizeUtil.formatSize(size));
			} else {
				fileInformationList.addChild("li", (l10n("sizeLabel") + ' ')+ SizeUtil.formatSize(size) + l10n("mayChange"));
			}
		} else {
			fileInformationList.addChild("li", l10n("sizeUnknown"));
		}
		if(mime != null) {
			fileInformationList.addChild("li", L10n.getString("FProxyToadlet."+(finalized ? "mimeType" : "expectedMimeType"), new String[] { "mime" }, new String[] { mime }));
		} else {
			fileInformationList.addChild("li", l10n("unknownMIMEType"));
		}
	}
	
	private String l10n(String key, String pattern, String value) {
		return L10n.getString("FProxyToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String getLink(FreenetURI uri, String requestedMimeType, long maxSize, String force, 
			boolean forceDownload) {
		StringBuilder sb = new StringBuilder();
		sb.append("/");
		sb.append(uri.toASCIIString());
		char c = '?';
		if(requestedMimeType != null) {
			sb.append(c).append("type=").append(URLEncoder.encode(requestedMimeType,false)); c = '&';
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
		String referer = ctx.getHeaders().get("referer");
		if(referer != null) {
			try {
				URI refererURI = new URI(referer);
				String path = refererURI.getPath();
				while(path.startsWith("/")) path = path.substring(1);
				if("".equals(path)) return "/";
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
			bos.write(Long.toString(time / FORCE_GRAIN_INTERVAL).getBytes("UTF-8"));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		String f = HexUtil.bytesToHex(SHA256.digest(bos.toByteArray()));
		return f;
	}

	public static void maybeCreateFProxyEtc(NodeClientCore core, Node node, Config config, SimpleToadletServer server, BookmarkManager bookmarks) throws IOException {
		
		// FIXME how to change these on the fly when the interface language is changed?
		
		HighLevelSimpleClient client = core.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS, true);
		
		random = new byte[32];
		core.random.nextBytes(random);
		FProxyToadlet fproxy = new FProxyToadlet(client, core);
		core.setFProxy(fproxy);
		server.register(fproxy, "/", false, "FProxyToadlet.welcomeTitle", "FProxyToadlet.welcome", false, null);
		
		UserAlertsToadlet alerts = new UserAlertsToadlet(client, node, core);
		server.register(alerts, "/alerts/", true, "FProxyToadlet.alertsTitle", "FProxyToadlet.alerts", true, null);
		
		PproxyToadlet pproxy = new PproxyToadlet(client, node, core);
		server.register(pproxy, "/plugins/", true, "FProxyToadlet.pluginsTitle", "FProxyToadlet.plugins", true, null);
		
		WelcomeToadlet welcometoadlet = new WelcomeToadlet(client, core, node, bookmarks);
		server.register(welcometoadlet, "/welcome/", true, false);
		
		ConfigToadlet configtoadlet = new ConfigToadlet(client, config, node, core);
		server.register(configtoadlet, "/config/", true, "FProxyToadlet.configTitle", "FProxyToadlet.config", true, null);
		
		SymlinkerToadlet symlinkToadlet = new SymlinkerToadlet(client, node);
		server.register(symlinkToadlet, "/sl/", true, false);
		
		DarknetConnectionsToadlet friendsToadlet = new DarknetConnectionsToadlet(node, core, client);
//		server.register(friendsToadlet, "/darknet/", true, l10n("friendsTitle"), l10n("friends"), true);
		server.register(friendsToadlet, "/friends/", true, "FProxyToadlet.friendsTitle", "FProxyToadlet.friends", true, null);
		
		OpennetConnectionsToadlet opennetToadlet = new OpennetConnectionsToadlet(node, core, client);
//		server.register(opennetToadlet, "/opennet/", true, l10n("opennetTitle"), l10n("opennet"), true, opennetToadlet);
		server.register(opennetToadlet, "/strangers/", true, "FProxyToadlet.opennetTitle", "FProxyToadlet.opennet", true, opennetToadlet);
		
		N2NTMToadlet n2ntmToadlet = new N2NTMToadlet(node, core, client);
		server.register(n2ntmToadlet, "/send_n2ntm/", true, true);
		
		QueueToadlet queueToadlet = new QueueToadlet(core, core.getFCPServer(), client);
		server.register(queueToadlet, "/queue/", true, "FProxyToadlet.queueTitle", "FProxyToadlet.queue", false, queueToadlet);
		
		StatisticsToadlet statisticsToadlet = new StatisticsToadlet(node, core, client);
		server.register(statisticsToadlet, "/stats/", true, "FProxyToadlet.statsTitle", "FProxyToadlet.stats", true, null);
		
		LocalFileInsertToadlet localFileInsertToadlet = new LocalFileInsertToadlet(core, client);
		server.register(localFileInsertToadlet, "/files/", true, false);
		
		BookmarkEditorToadlet bookmarkEditorToadlet = new BookmarkEditorToadlet(client, core, bookmarks);
		server.register(bookmarkEditorToadlet, "/bookmarkEditor/", true, false);
		
		BrowserTestToadlet browsertTestToadlet = new BrowserTestToadlet(client, core);
		server.register(browsertTestToadlet, "/test/", true, false);
		
		ConnectivityToadlet connectivityToadlet = new ConnectivityToadlet(client, node, core);
		server.register(connectivityToadlet, "/connectivity/", true, "ConnectivityToadlet.connectivityTitle", "ConnectivityToadlet.connectivity", true, null);
		
		TranslationToadlet translationToadlet = new TranslationToadlet(client, core);
		server.register(translationToadlet, TranslationToadlet.TOADLET_URL, true, true);
		
		FirstTimeWizardToadlet firstTimeWizardToadlet = new FirstTimeWizardToadlet(client, node, core);
		server.register(firstTimeWizardToadlet, FirstTimeWizardToadlet.TOADLET_URL, true, false);
		
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
