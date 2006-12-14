/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.plugin.api;

/**
 * A Freenet plugin. Base interface, must be implemented by all plugins.
 * Other interfaces provide for e.g. HTTP access. FreenetPluginManager provides
 * access to variables, factories etc.
 */
public interface FreenetPlugin {

	/** The plugin's short name (shouldn't usually have spaces, punctuation, author etc; one to three words StuckTogetherLikeThis) */
	public String shortName();
	
	/** The plugin's description or long name (can have spaces, mention author or purpose, etc) */
	public String description();
	
	/** The plugin's version number. MUST BE AT LEAST INCREMENTED ON EVERY RELEASE. */
	public long version();
	
	/** The plugin's internal version number e.g. SVN revision number. Plugins hosted
	 * by FPI will have this as SVN revision number and auto-updated. */
	public long internalVersion();
	
	/** 
	 * Start the plugin. This will run on a separate thread unless FreenetPluginThreadless is
	 * implemented.
	 * @param manager The parent FreenetPluginManager. Any variables or functions the plugin
	 * needs to access will be exposed by the FreenetPluginManager.
	 */
	public void start(FreenetPluginManager manager);
	
	/**
	 * Stop the plugin. Perform an orderly shutdown and return in a reasonable period of time.
	 */
	public void stop();
}
