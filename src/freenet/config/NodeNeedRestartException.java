/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

/**
 * Thrown when the node must be restarted for a config setting to be applied.
 * The thrower must ensure that the value reaches the config file, even though
 * it cannot be immediately used.
 */
@SuppressWarnings("serial")
public class NodeNeedRestartException extends ConfigException {
	public NodeNeedRestartException(String msg) {
		super(msg);
	}

}
