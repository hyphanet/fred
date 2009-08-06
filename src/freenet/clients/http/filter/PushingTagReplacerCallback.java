package freenet.clients.http.filter;

import java.net.MalformedURLException;
import java.net.URISyntaxException;

import freenet.client.FetchException;
import freenet.clients.http.FProxyFetchTracker;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.filter.HTMLFilter.ParsedTag;
import freenet.clients.http.updateableelements.ImageElement;
import freenet.clients.http.updateableelements.XmlAlertElement;
import freenet.keys.FreenetURI;
import freenet.l10n.L10n;

public class PushingTagReplacerCallback implements TagReplacerCallback{

	private FProxyFetchTracker	tracker;
	private long				maxSize;
	private ToadletContext		ctx;

	public PushingTagReplacerCallback(FProxyFetchTracker tracker, long maxSize, ToadletContext ctx) {
		this.tracker = tracker;
		this.maxSize = maxSize;
		this.ctx = ctx;
	}
	
	public static String getClientSideLocalizationScript(){
		StringBuilder l10nBuilder=new StringBuilder("var l10n={\n");
		for(String key:L10n.getAllNamesWithPrefix("ClientSide.GWT")){
			l10nBuilder.append(key.substring("ClientSide.GWT".length()+1)+": \""+L10n.getString(key)+"\",\n");
		}
		String l10n=l10nBuilder.substring(0, l10nBuilder.length()-2);
		l10n=l10n.concat("\n};");
		return l10n;
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
			}else if(pt.element.compareTo("body")==0){

				
				return "<body>".concat(new XmlAlertElement(ctx).generate().concat("<input id=\"requestId\" type=\"hidden\" value=\""+ctx.getUniqueId()+"\" name=\"requestId\"/>")).concat("<script type=\"text/javascript\" language=\"javascript\">".concat(getClientSideLocalizationScript()).concat("</script>"));
			}else if(pt.element.compareTo("head")==0){
				return "<head><script type=\"text/javascript\" language=\"javascript\" src=\"/static/freenetjs/freenetjs.nocache.js\"></script>";
			}
		}
		return null;
	}

}
