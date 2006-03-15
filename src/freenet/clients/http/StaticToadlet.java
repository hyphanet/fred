package freenet.clients.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.FileNameMap;
import java.net.URI;
import java.net.URLConnection;

import freenet.client.HighLevelSimpleClient;

/**
 * Static Toadlet.
 * Serve up static files
 */
public class StaticToadlet extends Toadlet {
	StaticToadlet(HighLevelSimpleClient client, CSSNameCallback CSSName) {
		super(client, CSSName);
	}
	
	final String rootURL = new String("/static/");
	final String rootPath = new String("staticfiles/");
	
	public void handleGet(URI uri, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String path = uri.getPath();
		byte[] buf = new byte[1024];
		int len;
		
		if (!path.startsWith(rootURL)) {
			// we should never get any other path anyway
			return;
		}
		try {
			path = path.substring(rootURL.length());
		} catch (IndexOutOfBoundsException ioobe) {
			this.sendErrorPage(ctx, 404, "Path not found", "The path you specified doesn't exist");
			return;
		}
		
		// be very strict about what characters we allow in the path, since
		if (!path.matches("^[A-Za-z0-9\\._\\/\\-]*$") || path.indexOf("..") != -1) {
			this.sendErrorPage(ctx, 404, "Path not found", "The given URI contains disallowed characters.");
			return;
		}
		
		
		InputStream strm = getClass().getResourceAsStream(rootPath+path);
		if (strm == null) {
			this.sendErrorPage(ctx, 404, "Path not found", "The specified path does not exist.");
			return;
		}
		
		
		FileNameMap map = URLConnection.getFileNameMap();
		
		ctx.sendReplyHeaders(200, "OK", null, map.getContentTypeFor(path), strm.available());
		
		while ( (len = strm.read(buf)) > 0) {
			ctx.writeData(buf, 0, len);
		}
	}
	
	public String supportedMethods() {
		return "GET";
	}
}
