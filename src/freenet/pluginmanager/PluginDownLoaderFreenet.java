/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.Logger;

public class PluginDownLoaderFreenet extends PluginDownLoader<FreenetURI> {
	final HighLevelSimpleClient hlsc;

	PluginDownLoaderFreenet(HighLevelSimpleClient hlsc) {
		this.hlsc = hlsc.clone();
	}

	@Override
	public FreenetURI checkSource(String source) throws PluginNotFoundException {
		try {
			return new FreenetURI(source);
		} catch (MalformedURLException e) {
			Logger.error(this, "not a valid freenet key: " + source, e);
			throw new PluginNotFoundException("not a valid freenet key: " + source, e);
		}
	}

	@Override
	InputStream getInputStream(final PluginProgress progress) throws IOException, PluginNotFoundException {
		FreenetURI uri = getSource();
		while (true) {
			try {
				progress.setDownloading();
				hlsc.addEventHook(new ClientEventListener() {

					public void onRemoveEventProducer(ObjectContainer container) {
						// Ignore
					}

					public void receive(ClientEvent ce, ObjectContainer maybeContainer, ClientContext context) {
						if(ce instanceof SplitfileProgressEvent) {
							SplitfileProgressEvent split = (SplitfileProgressEvent) ce;
							if(split.finalizedTotal) {
								progress.setDownloadProgress(split.minSuccessfulBlocks, split.succeedBlocks, split.totalBlocks, split.failedBlocks, split.fatallyFailedBlocks, split.finalizedTotal);
							}
						}
					}
					
				});
				FetchResult fres = hlsc.fetch(uri);
				return fres.asBucket().getInputStream();
			} catch (FetchException e) {
				if ((e.getMode() == FetchException.PERMANENT_REDIRECT) || (e.getMode() == FetchException.TOO_MANY_PATH_COMPONENTS)) {
					uri = e.newURI;
					continue;
				}
				Logger.error(this, "error while fetching plugin: " + getSource(), e);
				throw new PluginNotFoundException("error while fetching plugin: " + getSource(), e);
			}
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
	
	@Override
	String getSHA256sum() throws PluginNotFoundException {
		return null;
	}

}
