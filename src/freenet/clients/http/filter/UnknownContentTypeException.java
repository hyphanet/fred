package freenet.clients.http.filter;

import freenet.support.HTMLEncoder;

public class UnknownContentTypeException extends UnsafeContentTypeException {
	private static final long serialVersionUID = -1;
	final String type;
	final String encodedType;
	
	public UnknownContentTypeException(String typeName) {
		this.type = typeName;
		encodedType = HTMLEncoder.encode(type);
	}
	
	public String getType() {
		return type;
	}

	public String getHTMLEncodedTitle() {
		return "Unknown and potentially dangerous content type: "+encodedType;
	}

	public String getRawTitle() {
		return "Unknown and potentially dangerous content type: "+type;
	}
	
	public String getExplanation() {
		return "<p>Your Freenet node does not know anything about this MIME type. " +
				"This means that your browser might do something dangerous in response " +
				"to downloading this file. For example, many formats can contain embedded images " +
				"or videos, which are downloaded from the web; this is by no means innocuous, " +
				"because they can ruin your anonymity and expose your IP address (if the attacker " +
				"runs the web site or has access to its logs). Hyperlinks to the Web can also be a " +
				"threat, for much the same reason, as can scripting, for this and other reasons.</p>";
	}
	
}
