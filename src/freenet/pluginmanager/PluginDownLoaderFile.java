/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import freenet.pluginmanager.PluginManager.PluginProgress;

public class PluginDownLoaderFile extends PluginDownLoader<File> {

	@Override
	public File checkSource(String source) {
		return new File(source);
	}

	@Override
	InputStream getInputStream(PluginProgress progress) throws IOException {
		return new FileInputStream(getSource());
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		int slashIndex = source.lastIndexOf('/');
		if(slashIndex == -1)
			slashIndex = source.lastIndexOf('\\');
		return source.substring(slashIndex + 1);
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		return null;
	}

	@Override
	void tryCancel() {
		// Definitely not supported.
	}

	@Override
	public boolean isCachingProhibited() {
		return true;
	}

}
