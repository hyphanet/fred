/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetchWaiter;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.Logger;

public class PluginDownLoaderFreenet extends PluginDownLoader<FreenetURI> {
	final HighLevelSimpleClient hlsc;
	final boolean desperate;
	final Node node;
	private boolean fatalFailure;
	private ClientGetter get;

	PluginDownLoaderFreenet(HighLevelSimpleClient hlsc, Node node, boolean desperate) {
		this.hlsc = hlsc.clone();
		this.node = node;
		this.desperate = desperate;
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
		System.out.println("Downloading plugin from Freenet: "+uri);
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
				FetchContext context = hlsc.getFetchContext();
				if(desperate) {
					context.maxNonSplitfileRetries = -1;
					context.maxSplitfileBlockRetries = -1;
				}
				FetchWaiter fw = new FetchWaiter();

				get = new ClientGetter(fw, uri, context, PluginManager.PRIO, node.nonPersistentClientBulk, null, null);
				try {
					node.clientCore.clientContext.start(get);
				} catch (DatabaseDisabledException e) {
					// Impossible
				}
				FetchResult res = fw.waitForCompletion();
				return res.asBucket().getInputStream();
			} catch (FetchException e) {
				if ((e.getMode() == FetchException.PERMANENT_REDIRECT) || (e.getMode() == FetchException.TOO_MANY_PATH_COMPONENTS)) {
					uri = e.newURI;
					continue;
				}
				if(e.isFatal())
					fatalFailure = true;
				Logger.error(this, "error while fetching plugin: " + getSource(), e);
				throw new PluginNotFoundException("error while fetching plugin: " + e.getMessage() + " for key "  + getSource(), e);
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

	public boolean fatalFailure() {
		return fatalFailure;
	}

	@Override
	void tryCancel() {
		if(get != null)
			get.cancel(null, node.clientCore.clientContext);
	}

}
