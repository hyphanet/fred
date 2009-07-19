package freenet.clients.http.filter;

public interface URIProcessor {

	public String processURI(String u, String overrideType, boolean noRelative, boolean inline) throws CommentException;
}
