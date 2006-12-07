/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

public class NullFilterCallback implements FilterCallback {

	public String processURI(String uri, String overrideType) {
		return null;
	}

	public String onBaseHref(String baseHref) {
		return null;
	}

	public void onText(String s, String type) {
		// Do nothing
	}

	public String processForm(String method, String action) {
		return null;
	}

}
