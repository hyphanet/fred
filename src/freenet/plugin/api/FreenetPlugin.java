package freenet.plugin.api;

/**
 * A Freenet plugin. Base interface, must be implemented by all plugins.
 * Other interfaces provide for e.g. HTTP access. FreenetPluginManager provides
 * access to variables, factories etc.
 */
public interface FreenetPlugin {

	/** The plugin's name */
	public String name();
	
	/** The plugin's author (largely to disambiguate name!) */
	public String author();
	
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
