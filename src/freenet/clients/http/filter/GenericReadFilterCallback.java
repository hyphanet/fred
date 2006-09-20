package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashSet;

import freenet.clients.http.HTTPRequest;
import freenet.keys.FreenetURI;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URIPreEncoder;

public class GenericReadFilterCallback implements FilterCallback {
	public static final String magicHTTPEscapeString = "_CHECKED_HTTP_";
	public static final HashSet allowedProtocols;
	
	static {
		allowedProtocols = new HashSet();
		allowedProtocols.add("http");
		allowedProtocols.add("https");
		allowedProtocols.add("ftp");
		allowedProtocols.add("mailto");
		allowedProtocols.add("nntp");
		allowedProtocols.add("news");
		allowedProtocols.add("snews");
		allowedProtocols.add("about");
		allowedProtocols.add("irc");
		// file:// ?
	}

	private URI baseURI;
	private final FoundURICallback cb;
	
	public GenericReadFilterCallback(URI uri, FoundURICallback cb) {
		this.baseURI = uri;
		this.cb = cb;
	}
	
	public GenericReadFilterCallback(FreenetURI uri, FoundURICallback cb) {
		try {
			this.baseURI = new URI("/" + uri.toString(false));
			this.cb = cb;
		} catch (URISyntaxException e) {
			throw new Error(e);
		}
	}
	
	public boolean allowGetForms() {
		return false;
	}

	public boolean allowPostForms() {
		return false;
	}

	public String processURI(String u, String overrideType) {
		return processURI(u, overrideType, false);
	}
	
	public String processURI(String u, String overrideType, boolean noRelative) {
		URI uri;
		URI resolved;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			if(logMINOR) Logger.minor(this, "Processing "+u);
			uri = URIPreEncoder.encodeURI(u).normalize();
			if(logMINOR) Logger.minor(this, "Processing "+uri);
			if(!noRelative)
				resolved = baseURI.resolve(uri);
			else
				resolved = uri;
			if(logMINOR) Logger.minor(this, "Resolved: "+resolved);
		} catch (URISyntaxException e1) {
			if(logMINOR) Logger.minor(this, "Failed to parse URI: "+e1);
			return null;
		}
		String path = uri.getPath();
		
		HTTPRequest req = new HTTPRequest(uri);
		if (path != null){
			if(path.equals("/") && req.isParameterSet("newbookmark")){
				// allow links to the root to add bookmarks
				String bookmark_key = req.getParam("newbookmark");
				String bookmark_desc = req.getParam("desc");

				bookmark_key = HTMLEncoder.encode(bookmark_key);
				bookmark_desc = HTMLEncoder.encode(bookmark_desc);

				return "/?newbookmark="+bookmark_key+"&desc="+bookmark_desc;
			}else if(path.equals("") && uri.toString().matches("^#[a-zA-Z0-9-_]+$")){
				// Hack for anchors, see #710
				return uri.toString();
			}
		}
		
		// Try as an absolute URI
		
		String rpath = uri.getPath();
		if(rpath != null) {
			if(logMINOR) Logger.minor(this, "Resolved URI: "+rpath);
			
			// Valid FreenetURI?
			try {
				String p = rpath;
				while(p.startsWith("/")) p = p.substring(1);
				FreenetURI furi = new FreenetURI(p);
				if(logMINOR) Logger.minor(this, "Parsed: "+furi);
				return processURI(furi, uri, overrideType, noRelative);
			} catch (MalformedURLException e) {
				// Not a FreenetURI
			}
		}
		
		// Probably a relative URI.
		
		rpath = resolved.getPath();
		if(rpath == null) return null;
		if(logMINOR) Logger.minor(this, "Resolved URI: "+rpath);
		
		// Valid FreenetURI?
		try {
			String p = rpath;
			while(p.startsWith("/")) p = p.substring(1);
			FreenetURI furi = new FreenetURI(p);
			if(logMINOR) Logger.minor(this, "Parsed: "+furi);
			return processURI(furi, uri, overrideType, noRelative);
		} catch (MalformedURLException e) {
			// Not a FreenetURI
		}
		
		if(GenericReadFilterCallback.allowedProtocols.contains(uri.getScheme()))
			return "/?"+GenericReadFilterCallback.magicHTTPEscapeString+"="+uri;	
		else
			return null;
	}

	private String finishProcess(HTTPRequest req, String overrideType, String path, URI u, boolean noRelative) {
		String typeOverride = req.getParam("type", null);
		if(overrideType != null)
			typeOverride = overrideType;
		// REDFLAG any other options we should support? 
		// Obviously we don't want to support ?force= !!
		// At the moment, ?type= and ?force= are the only options supported by FProxy anyway.
		
		try {
			URI uri = new URI(null, null, path, typeOverride == null ? null : "type="+typeOverride,
					u.getFragment());
			if(!noRelative)
				uri = baseURI.relativize(uri);
			return uri.toASCIIString();
		} catch (URISyntaxException e) {
			Logger.error(this, "Could not parse own URI: path="+path+", typeOverride="+typeOverride+", frag="+u.getFragment()+" : "+e, e);
			String p = path;
			if(typeOverride != null)
				p += "?type="+typeOverride;
			if(u.getFragment() != null){
				try{
				// FIXME encode it properly
					p += URLEncoder.encode(u.getFragment(),"UTF-8");
				}catch (UnsupportedEncodingException e1){
				}
			}
			return null;
		}
	}

	private String processURI(FreenetURI furi, URI uri, String overrideType, boolean noRelative) {
		// Valid freenet URI, allow it
		// Now what about the queries?
		HTTPRequest req = new HTTPRequest(uri);
		if(cb != null) cb.foundURI(furi);
		return finishProcess(req, overrideType, "/" + furi.toString(false), uri, noRelative);
	}

	public String onBaseHref(String baseHref) {
		String ret = processURI(baseHref, null, true);
		if(ret == null) {
			Logger.error(this, "onBaseHref() failed: cannot sanitize "+baseHref);
			return null;
		} else {
			try {
				baseURI = new URI(ret);
			} catch (URISyntaxException e) {
				throw new Error(e); // Impossible
			}
			return baseURI.toASCIIString();
		}
	}

	public void onText(String s, String type) {
		if(cb != null)
			cb.onText(s, type, baseURI);
	}
	
}
