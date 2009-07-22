package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.net.URISyntaxException;

import freenet.client.FetchException;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.filter.HTMLFilter.ParsedTag;
import freenet.clients.http.updateableelements.ImageElement;
import freenet.keys.FreenetURI;

public class TagReplacerCallback {

	private FProxyFetchTracker	tracker;
	private long				maxSize;
	private ToadletContext		ctx;

	public TagReplacerCallback(FProxyFetchTracker tracker, long maxSize, ToadletContext ctx) {
		this.tracker = tracker;
		this.maxSize = maxSize;
		this.ctx = ctx;
	}

	public String processTag(ParsedTag pt, URIProcessor uriProcessor) {
		if (ctx.getContainer().isFProxyJavascriptEnabled()) {
			if (pt.element.compareTo("img") == 0) {
				for (int i = 0; i < pt.unparsedAttrs.length; i++) {
					String attr = pt.unparsedAttrs[i];
					String name = attr.substring(0, attr.indexOf("="));
					String value = attr.substring(attr.indexOf("=") + 2, attr.length() - 1);
					if (name.compareTo("src") == 0) {
						String src;
						try {
							src = uriProcessor.makeURIAbsolute(uriProcessor.processURI(value, null, false, false));
						} catch (CommentException ce) {
							src = value;
						} catch (URISyntaxException use) {
							src = value;
						}
						if (src.startsWith("/")) {
							src = src.substring(1);
						}
						try {
							return new ImageElement(tracker, new FreenetURI(src), maxSize, ctx, pt).generate();
						} catch (FetchException fe) {
							return null;
						} catch (MalformedURLException mue) {
							return null;
						}
					}
				}
			}
		}
		return null;
	}

}
