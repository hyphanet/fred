/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



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
