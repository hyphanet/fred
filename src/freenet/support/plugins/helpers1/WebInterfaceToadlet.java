/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.plugins.helpers1;

import java.util.List;

import freenet.clients.http.InfoboxNode;
import freenet.clients.http.LinkEnabledCallback;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.keys.FreenetURI;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public abstract class WebInterfaceToadlet extends Toadlet implements LinkEnabledCallback {

	protected final PluginContext pluginContext;

	private final String _path;

	protected WebInterfaceToadlet(PluginContext pluginContext2, String pluginURL, String pageName) {
		super(pluginContext2.hlsc);
		pluginContext = pluginContext2;
		_path = pluginURL + "/" + pageName;
	}

	@Override
	public String path() {
		return _path;
	}

	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return true;
	}

	/**
	 * returns allways at min a "/", but "/path" is allways without trailing '/'
	 * so "/path/to/toadlet/blah" and "/path/to/toadlet/blah/" cant be distinguished
	 * 
	 * @param path
	 * @return the path without "/path/to/toadlet"
	 */
	protected String normalizePath(String path) {
		String result = path.substring(_path.length());
		if (result.length() == 0) {
			return "/";
		}
		if (result.endsWith("/")) {
			result = result.substring(0, result.length()-1);
		}
		return result;
	}

	/**
	 * @param req
	 * @return true if the form password is valid
	 */
	protected boolean isFormPassword(HTTPRequest req) {
		String passwd = req.getParam("formPassword", null);
		if (passwd == null)
			passwd = req.getPartAsStringFailsafe("formPassword", 32);
		return (passwd != null) && passwd.equals(pluginContext.clientCore.formPassword);
	}

	public HTMLNode createErrorBox(List<String> errors) {
		return createErrorBox(errors, null, null, null);
	}

	public HTMLNode createErrorBox(List<String> errors, String path, FreenetURI retryUri, String extraParams) {
		InfoboxNode box = pluginContext.pageMaker.getInfobox("infobox-alert", "ERROR");
		HTMLNode errorBox = box.content;
		for (String error : errors) {
			errorBox.addChild("#", error);
			errorBox.addChild("br");
		}
		if (retryUri != null) {
			errorBox.addChild("#", "Retry: ");
			errorBox.addChild(new HTMLNode("a", "href", path + "?key="
					+ ((extraParams == null) ? retryUri : (retryUri + extraParams)), retryUri.toString(false, false)));
		}
		return box.outer;
	}
}
