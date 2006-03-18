package freenet.clients.http.filter;

public class KnownUnsafeContentTypeException extends UnsafeContentTypeException {

	MIMEType type;
	
	public KnownUnsafeContentTypeException(MIMEType type) {
		this.type = type;
	}

	public String getExplanation() {
		StringBuffer sb = new StringBuffer();
		sb.append("<p><b>");
		sb.append(type.readDescription);
		sb.append("</b></p>\n" +
			"<p>This is a potentially dangerous MIME type. If the node lets it through, your browser may " +
			"do bad things leading to compromize of your anonymity, and your IP address being exposed in "+
			"connection with this page. In particular:<ul>");
		if(type.dangerousInlines) 
			sb.append("<li><font color=\"red\"><b>Dangerous inlines:</b></font> This type of content can contain inline images or "+
					"videos, and can therefore load content from the non-anonymous open Web, exposing your "+
					"IP address.</li>");
		if(type.dangerousLinks)
			sb.append("<li><font color=\"red\"><b>Dangerous links:</b></font> This type of content can contain links to the "+
					"non-anonymous Web; if you click on them (and they may be disguised), this may expose "+
					"your IP address.</li>");
		if(type.dangerousScripting)
			sb.append("<li><font color=\"red\"><b>Dangerous scripting:</b></font> This type of content can contain dangerous scripts "+
					"which when executed may compromize your anonymity by connecting to the open Web or "+
					"otherwise breach security.</li>");
		if(type.dangerousReadMetadata)
			sb.append("<li><font color=\"red\"><b>Dangerous metadata:</b></font> This type of content can contain metadata which may "+
					"be displayed by some browsers or other software, which may contain dangerous links or inlines.</li>");
		
		sb.append("</ul>Since there is no built-in filter for this data, you should take the utmost of care!");
		
		return sb.toString();
	}

	public String getHTMLEncodedTitle() {
		return "Known dangerous type: "+type.primaryMimeType;
	}

	public String getRawTitle() {
		return "Known dangerous type: "+type.primaryMimeType;
	}

}
