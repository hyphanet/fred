/**
 * Provides the API necessary to write a plugin for Freenet. Freenet plugins run in
 * the same JVM as the node, but they can only access it through a limited API. All
 * plugins must implement the FreenetPlugin interface, and zero or more of the 
 * Needs* interfaces depending on what services they require from the node. These 
 * interfaces indicate to the node what services are required by the plugin; the node
 * may ask the user whether (s)he wants to allow the plugin to use these services.
 * 
 * @see freenet.support.api
 */
package freenet.plugin.api;