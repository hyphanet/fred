package freenet.clients.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.clients.http.PageMaker.THEME;
import freenet.support.HTMLNode;
import freenet.support.URLEncodedFormatException;
import freenet.support.api.BucketFactory;

public class NullToadletContainer implements ToadletContainer {
	
	public static NullToadletContainer instance = new NullToadletContainer();

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		HTMLNode formNode =
			parentNode.addChild("form", new String[] { "action", "method", "enctype", "id",  "accept-charset" }, 
					new String[] { target, "post", "multipart/form-data", id, "utf-8"} ).addChild("div");
		
		return formNode;
	}

	public boolean allowPosts() {
		return false;
	}

	public boolean doRobots() {
		return false;
	}

	public boolean enableInlinePrefetch() {
		return false;
	}

	public boolean enablePersistentConnections() {
		return false;
	}

	public Toadlet findToadlet(URI uri) throws PermanentRedirectException {
		return null;
	}

	public String generateSID(String realPath) throws URLEncodedFormatException {
		return null;
	}

	public BucketFactory getBucketFactory() {
		return null;
	}

	public String getFormPassword() {
		return null;
	}

	public THEME getTheme() {
		return null;
	}

	public boolean isAllowedFullAccess(InetAddress remoteAddr) {
		return false;
	}

	public boolean isSecureIDCheckingDisabled() {
		return true;
	}

	public boolean publicGatewayMode() {
		return false;
	}

	public void register(Toadlet t, String urlPrefix, boolean atFront,
			boolean fullAccessOnly) {
		throw new UnsupportedOperationException();
	}

	public String fixLink(String orig) {
		return orig;
	}

	public URI fixLink(URI uri) throws URISyntaxException {
		return uri;
	}

	public boolean enableActivelinks() {
		return false;
	}

}
