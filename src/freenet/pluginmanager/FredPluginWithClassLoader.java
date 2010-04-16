package freenet.pluginmanager;

/**
 * A plugin which needs to know its ClassLoader. This is usually necessary for db4o.
 * 
 * @author xor
 * @deprecated Use PluginClass.class.getClassLoader() instead!
 */
@Deprecated
public interface FredPluginWithClassLoader {
	
	public void setClassLoader(ClassLoader myClassLoader);
	
}
