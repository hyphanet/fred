package freenet.clients.http.filter;

import java.net.URISyntaxException;

public interface URIProcessor {

	public String processURI(String u, String overrideType, boolean noRelative, boolean inline) throws CommentException;
	
	public String makeURIAbsolute(String uri) throws URISyntaxException;
}
