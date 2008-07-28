/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import freenet.support.Logger;

public class PluginDownLoaderURL extends PluginDownLoader<URL> {

	public URL checkSource(String source) throws PluginNotFoundException {
		try {
			return new URL(source);
		} catch (MalformedURLException e) {
			Logger.error(this, "could not build plugin url for " + source, e);
			throw new PluginNotFoundException("could not build plugin url for " + source, e);
		}
	}

	@Override
	InputStream getInputStream() throws IOException {
		URLConnection urlConnection = getSource().openConnection();
		urlConnection.setUseCaches(false);
		urlConnection.setAllowUserInteraction(false);
		urlConnection.connect();
		return urlConnection.getInputStream();
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		String name = source.substring(source.lastIndexOf('/') + 1);
		if (name.endsWith(".url")) {
			name = name.substring(0, name.length() - 4);
		}
		return name;
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		return null;
	}

}
