/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.keys.FreenetURI;
import freenet.support.Logger;

public class PluginDownLoaderFreenet extends PluginDownLoader<FreenetURI> {
	
	final HighLevelSimpleClient hlsc;
	
	PluginDownLoaderFreenet(HighLevelSimpleClient hlsc) {
		this.hlsc = hlsc;
	}

	public FreenetURI checkSource(String source) throws PluginNotFoundException {
		try {
			return new FreenetURI(source);
		} catch (MalformedURLException e) {
			Logger.error(this, "not a valid freenet key: " + source, e);
			throw new PluginNotFoundException("not a valid freenet key: " + source, e);
		}
	}

	@Override
	InputStream getInputStream() throws IOException, PluginNotFoundException {
		try {
			FetchResult fres = hlsc.fetch(getSource());
			return fres.asBucket().getInputStream();
		} catch (FetchException e) {
			Logger.error(this, "error while fetching plugin: " + getSource(), e);
			throw new PluginNotFoundException("error while fetching plugin: " + getSource(), e);
		}
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		return source.substring(source.lastIndexOf('/') + 1);
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		return null;
	}

}
