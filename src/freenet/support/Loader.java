package freenet.support;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Hashtable;

/**
 * @author <a href=mailto:blanu@uts.cc.utexas.edu>Brandon Wiley</a>
 * @author oskar (I made this a generic loader, not just for messages). 
 **/

public class Loader {

    static final private Hashtable<String, Class<?>> classes = new Hashtable<String, Class<?>>();
    //  static final public String prefix="freenet.message.";

    /**
     * This is a caching Class loader.
     * @param name The name of the class to load.
     **/
    static public Class<?> load(String name) throws ClassNotFoundException {
		Class<?> c = classes.get(name);
	if(c==null) {
	    c=Class.forName(name);
	    classes.put(name, c);
	}
	return c;
    }

    /**
     * Creates a new object of given classname.
     * @param classname  Name of class to instantiate.
     **/
    public static Object getInstance(String classname) 
	throws InvocationTargetException, NoSuchMethodException, 
	       InstantiationException, IllegalAccessException,
	       ClassNotFoundException {
	return getInstance(classname, new Class<?>[] {}, new Object[] {});

    }

    /**
     * Creates a new object of given classname.
     * @param classname  Name of class to instantiate.
     * @param argtypes   The classes of the arguments.
     * @param args       The arguments. Since this uses the reflect methods
     *                   it's ok to wrap primitives.
     **/
    public static Object getInstance(String classname, Class<?>[] argtypes, 
				     Object[] args) 
	throws InvocationTargetException, NoSuchMethodException, 
	       InstantiationException, IllegalAccessException,
	       ClassNotFoundException {
	return getInstance(load(classname),argtypes,args);
    }

    /**
     * Creats a new object of a given class.
     * @param c          The class to instantiate.
     * @param argtypes   The classes of the arguments.
     * @param args       The arguments. Since this uses the reflect methods
     *                   it's ok to wrap primitives.
     **/
    public static Object getInstance(Class<?> c, Class<?>[] argtypes, 
				     Object[] args) 
	throws InvocationTargetException, NoSuchMethodException, 
	       InstantiationException, IllegalAccessException {
	Constructor<?> con = c.getConstructor(argtypes);
	return con.newInstance(args);
    }
}


