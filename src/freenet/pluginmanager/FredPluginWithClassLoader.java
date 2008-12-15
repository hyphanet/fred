package freenet.pluginmanager;

/**
 * A plugin which needs to know its ClassLoader. This is usually necessary for db4o.
 * 
 * @author xor
 *
 */
public interface FredPluginWithClassLoader {
	
	public void setClassLoader(ClassLoader myClassLoader);
	
}
