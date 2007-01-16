/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashSet;

import freenet.clients.http.HTTPRequestImpl;
import freenet.keys.FreenetURI;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URIPreEncoder;
import freenet.support.api.HTTPRequest;

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
			this.baseURI = uri.toRelativeURI();
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

	public String processURI(String u, String overrideType) throws CommentException {
		return processURI(u, overrideType, false);
	}
	
	public String processURI(String u, String overrideType, boolean noRelative) throws CommentException {
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
			throw new CommentException("Filter could not parse URI: "+e1.getMessage());
		}
		String path = uri.getPath();
		
		HTTPRequest req = new HTTPRequestImpl(uri);
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
		
		String reason = "";
		
		// Try as an absolute URI
		
		String rpath = uri.getPath();
		
		if(rpath != null) {
			if(logMINOR) Logger.minor(this, "Resolved URI (rpath absolute): "+rpath);
			
			// Valid FreenetURI?
			try {
				String p = rpath;
				while(p.startsWith("/")) p = p.substring(1);
				FreenetURI furi = new FreenetURI(p);
				if(logMINOR) Logger.minor(this, "Parsed: "+furi);
				return processURI(furi, uri, overrideType, noRelative);
			} catch (MalformedURLException e) {
				// Not a FreenetURI
				if(logMINOR) Logger.minor(this, "Malformed URL (a): "+e, e);
				reason = "Malformed URL (absolute): "+e.getMessage();
				if(e.getMessage() == null) {
					reason = "Could not be parsed as a Freenet URI";
				}
			}
		}
		
		// Probably a relative URI.
		
		rpath = resolved.getPath();
		if(rpath == null) throw new CommentException("No URI");
		if(logMINOR) Logger.minor(this, "Resolved URI (rpath relative): "+rpath);
		
		// Valid FreenetURI?
		try {
			String p = rpath;
			while(p.startsWith("/")) p = p.substring(1);
			FreenetURI furi = new FreenetURI(p);
			if(logMINOR) Logger.minor(this, "Parsed: "+furi);
			return processURI(furi, uri, overrideType, noRelative);
		} catch (MalformedURLException e) {
			if(logMINOR) Logger.minor(this, "Malformed URL (b): "+e, e);
			if(reason.length() > 0)
				reason += " , ";
			if(e.getMessage() != null)
				reason += "Malformed URL (relative): "+e.getMessage();
			else
				reason += "Could not be parsed as a (relative) Freenet URI";
			// Not a FreenetURI
		}
		
		if(GenericReadFilterCallback.allowedProtocols.contains(uri.getScheme()))
			return "/?"+GenericReadFilterCallback.magicHTTPEscapeString+ '=' +uri;
		else {
			if(uri.getScheme() == null) {
				throw new CommentException("Invalid freenet URL: "+reason);
			}
			throw new CommentException("Not an escaped protocol: "+uri.getScheme());
		}
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
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Returning "+uri.toASCIIString()+" from "+path+" from baseURI="+baseURI);
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
			return p;
		}
	}

	private String processURI(FreenetURI furi, URI uri, String overrideType, boolean noRelative) {
		// Valid freenet URI, allow it
		// Now what about the queries?
		HTTPRequest req = new HTTPRequestImpl(uri);
		if(cb != null) cb.foundURI(furi);
		return finishProcess(req, overrideType, '/' + furi.toString(false, false), uri, noRelative);
	}

	public String onBaseHref(String baseHref) {
		String ret;
		try {
			ret = processURI(baseHref, null, true);
		} catch (CommentException e1) {
			Logger.error(this, "Failed to parse base href: "+baseHref+" -> "+e1.getMessage());
			ret = null;
		}
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

	static final String PLUGINS_PREFIX = "/plugins/";
	
	/**
	 * Process a form.
	 * Current strategy:
	 * - Both POST and GET forms are allowed to /
	 * Anything that is hazardous should be protected through formPassword.
	 * @throws CommentException If the form element could not be parsed and the user should be told.
	 */
	public String processForm(String method, String action) throws CommentException {
		if(action == null) return null;
		if(method == null) method = "GET";
		method = method.toUpperCase();
		if(!(method.equals("POST") || method.equals("GET"))) 
			return null; // no irregular form sending methods
		// Everything is allowed to / - updating the node, shutting it down, everything.
		// Why? Becuase it's all protected by formPassword anyway.
		// FIXME whitelist? Most things are okay if the user is prompted for a confirmation...
		// FIXME what about /queue/ /darknet/ etc?
		if(action.equals("/")) 
			return action;
		try {
			URI uri = URIPreEncoder.encodeURI(action);
			if(uri.getScheme() != null || uri.getHost() != null || uri.getPort() != -1 || uri.getUserInfo() != null)
				throw new CommentException("Invalid form URI had scheme, user-info, host or port");
			String path = uri.getPath();
			if(path.startsWith(PLUGINS_PREFIX)) {
				String after = path.substring(PLUGINS_PREFIX.length());
				if(after.indexOf("/../") > -1)
					throw new CommentException("Attempt to escape directory structure");
				if(after.matches("[A-Za-z0-9\\.]+"))
					return uri.toASCIIString();
			}
		} catch (URISyntaxException e) {
			throw new CommentException("Could not encode form URI");
		}
		// Otherwise disallow.
		return null;
	}
	
}
