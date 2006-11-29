/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

public class PluginNotFoundException extends Exception {
	private static final long serialVersionUID = -1;

	public PluginNotFoundException() {
		super();
	}

	public PluginNotFoundException(String arg0) {
		super(arg0);
	}

	public PluginNotFoundException(String arg0, Throwable arg1) {
		super(arg0, arg1);
	}

	public PluginNotFoundException(Throwable arg0) {
		super(arg0);
	}

}
