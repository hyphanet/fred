/**
 * Support for plugins. Plugins are extensions that run within the same JVM
 * as Freenet. At present, plugins are not sandboxed in any way, and can 
 * access all of the node. Also, there isn't really a proper plugin API -
 * there are a few interfaces to make certain tasks easier or possible (e.g.
 * @see freenet.pluginmanager.FredPluginHTTP ), but mostly they just call
 * the classes they need.
 */
package freenet.pluginmanager;
