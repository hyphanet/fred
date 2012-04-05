/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.Logger;

public class PluginDownLoaderURL extends PluginDownLoader<URL> {

	@Override
	public URL checkSource(String source) throws PluginNotFoundException {
		try {
			return new URL(source);
		} catch (MalformedURLException e) {
			// Generate a meaningful error message when file not found falls back to a URL.
			// Maybe it's a file?
			// If we've reached this point then it doesn't exist.
			File[] roots = File.listRoots();
			for(File f : roots) {
				if(source.startsWith(f.getName()) && !new File(source).exists()) {
					throw new PluginNotFoundException("File not found: "+source);
				}
			}

			Logger.error(this, "could not build plugin url for " + source, e);
			throw new PluginNotFoundException("could not build plugin url for " + source, e);
		}
	}

	@Override
	InputStream getInputStream(PluginProgress progress) throws IOException {
		URLConnection urlConnection = getSource().openConnection();
		urlConnection.setUseCaches(false);
		urlConnection.setAllowUserInteraction(false);
		//urlConnection.connect();
		return openConnectionCheckRedirects(urlConnection);
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
	
	@Override
	String getSHA256sum() throws PluginNotFoundException {
		return null;
	}

	
	static InputStream openConnectionCheckRedirects(URLConnection c) throws IOException
	{
		boolean redir;
		int redirects = 0;
		InputStream in = null;
		do
		{
			if (c instanceof HttpURLConnection)
			{
				((HttpURLConnection) c).setInstanceFollowRedirects(false);
			}
			// We want to open the input stream before getting headers
			// because getHeaderField() et al swallow IOExceptions.
			in = c.getInputStream();
			redir = false;
			if (c instanceof HttpURLConnection)
			{
				HttpURLConnection http = (HttpURLConnection) c;
				int stat = http.getResponseCode();
				if (stat >= 300 && stat <= 307 && stat != 306 &&
						stat != HttpURLConnection.HTTP_NOT_MODIFIED)
				{
					URL base = http.getURL();
					String loc = http.getHeaderField("Location");
					URL target = null;
					if (loc != null)
					{
						target = new URL(base, loc);
					}
					http.disconnect();
					// Redirection should be allowed only for HTTP and HTTPS
					// and should be limited to 5 redirections at most.
					if (target == null || !(target.getProtocol().equals("http")
								|| target.getProtocol().equals("https")
								|| target.getProtocol().equals("ftp"))
							|| redirects >= 5)
					{
						throw new SecurityException("illegal URL redirect");
					}
					redir = true;
					c = target.openConnection();
					redirects++;
					in.close();
				}
			}
		}
		while (redir);
		return in;
	}

	@Override
	void tryCancel() {
		// Do nothing, not supported.
	}
}

