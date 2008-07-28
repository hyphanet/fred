/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.net.URL;

public class PluginDownLoaderOfficial extends PluginDownLoaderURL {

	public URL checkSource(String source) throws PluginNotFoundException {
		// FIXME use the new uri
		return super.checkSource("http://downloads.freenetproject.org/alpha/plugins/" + source + ".jar.url");
	//	return super.checkSource("https://checksums.freenetproject.org/latest/" + source + ".jar.url");
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		return source;
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		return null;
	}

}
