/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.IOException;
import java.io.InputStream;

/**
 * load a plugin from wherever 
 * @author saces
 *
 */
public abstract class PluginDownLoader<T> {
	
	private T source;

	public String setSource(String source) throws PluginNotFoundException {
		this.source = checkSource(source);
		return getPluginName(source);
	}

	public T getSource() {
		return source;
	}
	
	abstract InputStream getInputStream() throws IOException, PluginNotFoundException;
	
	abstract T checkSource(String source) throws PluginNotFoundException;
	
	abstract String getPluginName(String source) throws PluginNotFoundException;
	
	abstract String getSHA1sum() throws PluginNotFoundException;

}
