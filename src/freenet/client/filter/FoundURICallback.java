/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.net.URI;

import freenet.keys.FreenetURI;

public interface FoundURICallback {

	/**
	 * Called when a Freenet URI is found.
	 * @param uri The URI.
	 * FIXME: Indicate the type of the link e.g. inline image, hyperlink, etc??
	 */
	public void foundURI(FreenetURI uri);

	/**
	 * Called when a Freenet URI is found.
	 * @param uri The URI.
	 * FIXME: Indicate the type of the link e.g. inline image, hyperlink, etc??
	 */
	public void foundURI(FreenetURI uri, boolean inline);

	/**
	 * Called when some plain text is processed. This is used typically by
	 * spiders to index pages by their content.
	 * @param text The text. Will already have been fed through whatever decoding
	 * is necessary depending on the type of the source document e.g. HTMLDecoder.
	 * Will need to be re-encoded before being sent to e.g. a browser.
	 * @param type Can be null, or may be for example the name of the HTML tag
	 * directly surrounding the text. E.g. "title" lets you find page titles.
	 * @param baseURI The current base URI for this page. The base URI is not
	 * necessarily the URI of the page. It's the URI against which URIs on the
	 * page are resolved. It defaults to the URI of the page but can be overridden
	 * by base href in html, for example.	 */
	public void onText(String text, String type, URI baseURI);

	public void onFinishedPage();
	
}
