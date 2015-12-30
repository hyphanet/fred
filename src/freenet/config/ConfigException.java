/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

/**
 * Usefull if you want to catch all exceptions the config framework can return;
 */
public abstract class ConfigException extends Exception {
	private static final long serialVersionUID = -1;
	
	public ConfigException(String msg) {
		super(msg);
	}
	public ConfigException(Throwable cause) {
		super(cause);
	}
}
