package freenet.pluginmanager;

/**
 * A Fred plugin that has a version.
 * This is a user-presentable version, a marketing version, a string, 
 * not necessarily easy to compare! Like "1.0.3". Whereas a real version would be 103.
 * @see FredPluginRealVersioned for real versions.
 * @author dbkr
 *
 */
public interface FredPluginVersioned {
	public String getVersion();
}
