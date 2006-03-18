package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.keys.FreenetURI;
import freenet.pluginmanager.HTTPRequest;
import freenet.support.Logger;

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
		if(path.startsWith("/")) {
			// Try to make it into a FreenetURI
			try {
				FreenetURI furi = new FreenetURI(path.substring(1));
				return processURI(furi, uri, overrideType);
			} catch (MalformedURLException e) {
				// Obviously not a Freenet URI!
			}
		} else {
			// Relative URI
			// FIXME resolve it
			// FIXME Note that we allow links to / inlines from fproxy services.
			// This is okay because we don't allow forms.
			HTTPRequest req = new HTTPRequest(uri);
			return finishProcess(req, overrideType, path);
		}
		Logger.normal(this, "Unrecognized URI, dropped: "+uri);
		return null;
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
