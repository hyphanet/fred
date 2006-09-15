/*
  Loader.java / Freenet
  Copyright (C) Brandon Wiley <blanu@uts.cc.utexas.edu
  Copyright (C) oscar
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Hashtable;

/**
 * @author <a href=mailto:blanu@uts.cc.utexas.edu>Brandon Wiley</a>
 * @author oskar (I made this a generic loader, not just for messages). 
 **/

public class Loader {

    static final private Hashtable classes=new Hashtable();
    //  static final public String prefix="freenet.message.";

    /**
     * This is a caching Class loader.
     * @param name The name of the class to load.
     **/
    static public Class load(String name) throws ClassNotFoundException {
	Class c=(Class)classes.get(name);
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
	return getInstance(classname,new Class[] {}, new Object[] {});

    }

    /**
     * Creates a new object of given classname.
     * @param classname  Name of class to instantiate.
     * @param argtypes   The classes of the arguments.
     * @param args       The arguments. Since this uses the reflect methods
     *                   it's ok to wrap primitives.
     **/
    public static Object getInstance(String classname, Class[] argtypes, 
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
    public static Object getInstance(Class c, Class[] argtypes, 
				     Object[] args) 
	throws InvocationTargetException, NoSuchMethodException, 
	       InstantiationException, IllegalAccessException {
	Constructor con = c.getConstructor(argtypes);
	return con.newInstance(args);
    }
}


