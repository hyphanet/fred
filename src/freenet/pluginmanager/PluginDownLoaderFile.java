/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class PluginDownLoaderFile extends PluginDownLoader<File> {

	@Override
	public File checkSource(String source) {
		return new File(source);
	}

	@Override
	InputStream getInputStream() throws IOException {
		return new FileInputStream(getSource());
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
