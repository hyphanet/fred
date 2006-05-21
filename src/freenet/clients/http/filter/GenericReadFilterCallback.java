package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;

import freenet.clients.http.HTTPRequest;
import freenet.keys.FreenetURI;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;

public class GenericReadFilterCallback implements FilterCallback {

	private URI baseURI;
	
	public GenericReadFilterCallback(URI uri) {
		this.baseURI = uri;
	}
	
	public GenericReadFilterCallback(FreenetURI uri) {
		try {
			this.baseURI = new URI("/" + uri.toString(false));
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
		try {
			uri = new URI(u).normalize();
			Logger.minor(this, "Processing "+uri);
			if(!noRelative)
				resolved = baseURI.resolve(uri);
			else
				resolved = uri;
			Logger.minor(this, "Resolved: "+resolved);
		} catch (URISyntaxException e1) {
			return null;
		}
		String path = uri.getPath();
		
		// Normal protocols should go to /__CHECKED_HTTP_ for POST-form verification.
		// mailto: not supported yet - FIXME what to do with it? what queries are allowed? can it possibly hurt us? how to construct safely? etc
		
		HTTPRequest req = new HTTPRequest(uri);
		if (path != null && path.equals("/") && req.isParameterSet("newbookmark")) {
			// allow links to the root to add bookmarks
			String bookmark_key = req.getParam("newbookmark");
			String bookmark_desc = req.getParam("desc");
			
			bookmark_key = HTMLEncoder.encode(bookmark_key);
			bookmark_desc = HTMLEncoder.encode(bookmark_desc);
			
			return "/?newbookmark="+bookmark_key+"&desc="+bookmark_desc;
		}
		
		// Try as an absolute URI
		
		String rpath = uri.getPath();
		if(rpath != null) {
			Logger.minor(this, "Resolved URI: "+rpath);
			
			// Valid FreenetURI?
			try {
				String p = rpath;
				while(p.startsWith("/")) p = p.substring(1);
				FreenetURI furi = new FreenetURI(p);
				Logger.minor(this, "Parsed: "+furi);
				return processURI(furi, uri, overrideType, noRelative);
			} catch (MalformedURLException e) {
				// Not a FreenetURI
			}
		}
		
		// Probably a relative URI.
		
		rpath = resolved.getPath();
		if(rpath == null) return null;
		Logger.minor(this, "Resolved URI: "+rpath);
		
		// Valid FreenetURI?
		try {
			String p = rpath;
			while(p.startsWith("/")) p = p.substring(1);
			FreenetURI furi = new FreenetURI(p);
			Logger.minor(this, "Parsed: "+furi);
			return processURI(furi, uri, overrideType, noRelative);
		} catch (MalformedURLException e) {
			// Not a FreenetURI
		}
		
		return null;
	}

	private String finishProcess(HTTPRequest req, String overrideType, String path, URI u, boolean noRelative) {
		String typeOverride = req.getParam("type", null);
		if(overrideType != null)
			typeOverride = overrideType;
		// REDFLAG any other options we should support? 
		// Obviously we don't want to support ?force= !!
		// At the moment, ?type= and ?force= are the only options supported by Fproxy anyway.
		String ret = path;
		
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
	
}
