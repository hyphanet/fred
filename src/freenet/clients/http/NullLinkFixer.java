package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;

public final class NullLinkFixer implements LinkFixer {

	public static LinkFixer instance = new NullLinkFixer();

	public final String fixLink(String orig) {
		return orig;
	}

	public URI fixLink(URI uri) throws URISyntaxException {
		return uri;
	}

}
