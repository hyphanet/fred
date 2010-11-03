package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.Date;

import freenet.client.DefaultMIMETypes;
import freenet.l10n.NodeL10n;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

/**
 * Static Toadlet.
 * Serve up static files
 */
public class StaticToadlet extends Toadlet {
	StaticToadlet() {
		super(null);
	}
	
	public static final String ROOT_URL = "/static/";
	public static final String ROOT_PATH = "staticfiles/";
	
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		String path = uri.getPath();
		
		if (!path.startsWith(ROOT_URL)) {
			// we should never get any other path anyway
			return;
		}
		try {
			path = path.substring(ROOT_URL.length());
		} catch (IndexOutOfBoundsException ioobe) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
			return;
		}
		
		// be very strict about what characters we allow in the path, since
		if (!path.matches("^[A-Za-z0-9\\._\\/\\-]*$") || (path.indexOf("..") != -1)) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathInvalidChars"));
			return;
		}
		
		InputStream strm = getClass().getResourceAsStream(ROOT_PATH+path);
		if (strm == null) {
			this.sendErrorPage(ctx, 404, l10n("pathNotFoundTitle"), l10n("pathNotFound"));
			return;
		}
		Bucket data = ctx.getBucketFactory().makeBucket(strm.available());
		OutputStream os = data.getOutputStream();
		byte[] cbuf = new byte[4096];
		while(true) {
			int r = strm.read(cbuf);
			if(r == -1) break;
			os.write(cbuf, 0, r);
		}
		strm.close();
		os.close();
		
		URL url = getClass().getResource(ROOT_PATH+path);
		Date mTime = getUrlMTime(url);
		
		ctx.sendReplyHeaders(200, "OK", null, DefaultMIMETypes.guessMIMEType(path, false), data.size(), mTime);

		ctx.writeData(data);
	}
	
	/**
	 * Try to find the modification time for a URL, or return null if not possible
	 * We usually load our resources from the JAR, or possibly from a file in some setups, so we check the modification time of
	 * the JAR for resources in a jar and the mtime for files.
	 */
	private Date getUrlMTime(URL url) {
		if (url.getProtocol().equals("jar")) {
			File f = new File(url.getPath().substring(0, url.getPath().indexOf('!')));
			return new Date(f.lastModified());
		} else if (url.getProtocol().equals("file")) {
			File f = new File(url.getPath());
			return new Date(f.lastModified());
		} else {
			return null;
		}
	}
	
	private String l10n(String key) {
		return NodeL10n.getBase().getString("StaticToadlet."+key);
	}

	@Override
	public String path() {
		return ROOT_URL;
	}

}
