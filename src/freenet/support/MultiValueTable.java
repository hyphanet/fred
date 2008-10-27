package freenet.support;
import java.util.*;
/**
 * A hashtable that can store several values for each entry.
 *
 * @author oskar
 */

public class MultiValueTable {

    private Hashtable table;
    private int ies;

    public MultiValueTable() {
        this(16, 3);
    }

    public MultiValueTable(int initialSize) {
        this(initialSize, 3);
    }

    public MultiValueTable(int initialSize, int initialEntrySize) {
        table = new Hashtable(initialSize);
        ies = initialEntrySize;
    }

    public void put(Object key, Object value) {
        synchronized (table) {
            Vector v = (Vector) table.get(key);
            if (v == null) {
                v = new Vector(ies);
                table.put(key, v);
            }
            v.addElement(value);
        }
    }

    /**
     * Returns the first element for this key.
     */
    public Object get(Object key) {
        synchronized (table) {
            Vector v = (Vector) table.get(key);
            return (v == null ?
                    null :
                    v.firstElement());
        }
    }

    public boolean containsKey(Object key) {
		synchronized (table) {
			return table.containsKey(key);
		}
    }

    public boolean containsElement(Object key, Object value) {
        synchronized (table) {
            Vector v = (Vector) table.get(key);
            return (v != null) && v.contains(value);
        }
    }

    /**
     * Users will have to handle synchronizing.
     */
    public Enumeration getAll(Object key) {
    	Vector v;
		synchronized (table) {
			v = (Vector) table.get(key);
		}
        return (v == null ?
                new LimitedEnumeration(null) :
                v.elements());
    }
    
    public int countAll(Object key) {
    	Vector v;
		synchronized (table) {
			v = (Vector)table.get(key);
		}
    	if(v != null) 
        	return v.size();
        else
        	return 0;
    }
    
    public Object getSync(Object key) {
		synchronized (table) {
			return table.get(key);
		}
    }
    
    public Object[] getArray(Object key) {
        synchronized (table) {
            Vector v = (Vector) table.get(key);
            if (v == null)
                return null;
            else {
                Object[] r = new Object[v.size()];
                v.copyInto(r);
                return r;
            }
        }
    }

    public void remove(Object key) {
		synchronized (table) {
			table.remove(key);
		}
    }

    public boolean isEmpty() {
		synchronized (table) {
			return table.isEmpty();
		}
    }

    public void clear() {
		synchronized (table) {
			table.clear();
		}
    }

    public boolean removeElement(Object key, Object value) {
        synchronized (table) {
            Vector v = (Vector) table.get(key);
            if (v == null)
                return false;
            else {
                boolean b = v.removeElement(value);
                if (v.isEmpty())
                    table.remove(key);
                return b;
            }
        }
    }

    public Enumeration keys() {
		synchronized (table) {
			return table.keys();
		}
    }

    public Enumeration elements() {
		synchronized (table) {
			if (table.isEmpty())
				return new LimitedEnumeration(null);
			else 
				return new MultiValueEnumeration();
		}
    }

    private class MultiValueEnumeration implements Enumeration {
        private Enumeration current;
        private Enumeration global;
        public MultiValueEnumeration() {
			synchronized (table) {
				global = table.elements();
			}
            current = ((Vector) global.nextElement()).elements();
            step();
        }

        public final void step() {
            while (!current.hasMoreElements() && global.hasMoreElements())
                current = ((Vector) global.nextElement()).elements();
        }

        public final boolean hasMoreElements() {
            return global.hasMoreElements(); // || current.hasMoreElements();
        }
        
        public final Object nextElement() {
            Object o = current.nextElement();
            step();
            return o;
        }
    }

}
