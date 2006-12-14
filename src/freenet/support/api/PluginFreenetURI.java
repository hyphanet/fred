/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

/**
 * Base interface of FreenetURI.
 * Provides an interface for plugins.
 * Anything inside the node should use FreenetURI directly.
 * Any dangerous methods should be left out of this interface.
 * BaseFreenetURI's are constructed via FreenetPluginManager.
 */
public interface PluginFreenetURI {

}
