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
