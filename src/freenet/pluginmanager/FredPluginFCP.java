/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPMessage;
import freenet.support.SimpleFieldSet;

/**
 * Interface that has to be implemented for plugins that want to add fcp commands
 * 
 * the namesheme is plugin.class.commandname
 * the node looks for the plugin "plugin.class" (the implementor of this interface)
 * and ask him for the FCPCommand corresponding to "commandname".
 * If the plugin don't know what to do with "commandname" it returns null.
 * 
 * see plugins.FCPHello for a simple sample.
 * 
 * @author saces
 *
 */
public interface FredPluginFCP {
	
	FCPMessage create(String name, SimpleFieldSet fs, boolean fullacess);
	
}
