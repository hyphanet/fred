package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.keys.FreenetURI;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.Logger;
import freenet.support.HTMLEncoder;
import freenet.support.URLEncoder;

public class GenericReadFilterCallback implements FilterCallback {

	public boolean allowGetForms() {
		return false;
	}

	public boolean allowPostForms() {
		return false;
	}

	public String processURI(String u, String overrideType) {
		URI uri;
		try {
			uri = new URI(u);
		} catch (URISyntaxException e1) {
			return null;
		}
		String path = uri.getPath();
		if (path == null) {
			// Only fragment?
			if(uri.getScheme() == null && uri.getFragment() != null && 
					uri.getHost() == null) {
				return "#" + URLEncoder.encode(uri.getFragment());
			}
			return null;
		}
		// mailto: not supported yet - FIXME what to do with it? what queries are allowed? can it possibly hurt us? how to construct safely? etc
		
		HTTPRequest req = new HTTPRequest(uri);
		if (path.equals("/") && req.isParameterSet("newbookmark")) {
			// allow links to the root to add bookmarks
			String bookmark_key = req.getParam("newbookmark");
			String bookmark_desc = req.getParam("desc");
			
			bookmark_key = HTMLEncoder.encode(bookmark_key);
			bookmark_desc = HTMLEncoder.encode(bookmark_desc);
			
			return "/?newbookmark="+bookmark_key+"&desc="+bookmark_desc;
		} else if(path.startsWith("/") || path.indexOf('@') != -1) {
			// Try to make it into a FreenetURI
			try {
				String p = path;
				while(p.startsWith("/")) p = p.substring(1);
				FreenetURI furi = new FreenetURI(p);
				return processURI(furi, uri, overrideType);
			} catch (MalformedURLException e) {
				// Obviously not a Freenet URI!
			}
		}
		if(path.startsWith("/")) {
			// Still here. It's an absolute URI and *NOT* a freenet URI.
			// Kill it.
			Logger.normal(this, "Unrecognized URI, dropped: "+uri);
			return null;
		} else {
			// Relative URI
			// FIXME resolve it
			// FIXME Note that we allow links to / inlines from fproxy services.
			// This is okay because we don't allow forms.
			return finishProcess(req, overrideType, path);
		}
	}

	private String finishProcess(HTTPRequest req, String overrideType, String path) {
		String typeOverride = req.getParam("type", null);
		if(overrideType != null)
			typeOverride = overrideType;
		// REDFLAG any other options we should support? 
		// Obviously we don't want to support ?force= !!
		// At the moment, ?type= and ?force= are the only options supported by Fproxy anyway.
		String ret = path;
		if(typeOverride != null)
			ret = ret + "?type=" + typeOverride;
		return ret;
	}

	private String processURI(FreenetURI furi, URI uri, String overrideType) {
		// Valid freenet URI, allow it
		// Now what about the queries?
		HTTPRequest req = new HTTPRequest(uri);
		return finishProcess(req, overrideType, "/" + furi.toString(false));
	}
	
}
