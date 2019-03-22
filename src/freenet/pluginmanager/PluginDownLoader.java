/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;

import freenet.pluginmanager.PluginManager.PluginProgress;

/**
 * load a plugin from wherever 
 * @author saces
 *
 */
public abstract class PluginDownLoader<T> {
	
	private T _source;

	public String setSource(String source) throws PluginNotFoundException {
		this._source = checkSource(source);
		return getPluginName(source);
	}

	public T getSource() {
		return _source;
	}
	
	abstract InputStream getInputStream(PluginProgress progress) throws IOException, PluginNotFoundException;
	
	abstract T checkSource(String source) throws PluginNotFoundException;
	
	abstract String getPluginName(String source) throws PluginNotFoundException;
	
	abstract String getSHA1sum() throws PluginNotFoundException;

	/** Cancel the load if possible */
	abstract void tryCancel();

	public boolean isCachingProhibited() {
		return false;
	}

	public boolean isOfficialPluginLoader() {
		return false;
	}

	public boolean isLoadingFromFreenet() {
		return false;
	}

    /**
     * Returns a {@link PluginDownLoader} that can be used to restart a plugin download. This
     * default implementation assumes that the current downloader is stateless so it simply returns
     * {@code this}.
     *
     * @return A plugin downloader suitable for restarting the download
     */
    public PluginDownLoader<T> getRetryDownloader() {
        return this;
    }

}
