package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;

public interface LinkFixer {

	public abstract String fixLink(String orig);

	public abstract URI fixLink(URI uri) throws URISyntaxException;

}