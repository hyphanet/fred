package freenet.pluginmanager;

/**
 * Exception that signals that when requesting to load a plugin it was determined that a plugin
 * with the same main class name is already loaded.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class PluginAlreadyLoaded extends Exception {

}
