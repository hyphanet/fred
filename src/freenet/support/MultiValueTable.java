package freenet.support;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Vector;

/**
 * A hashtable that can store several values for each entry.
 *
 * FIXME improve efficiency - map to Object internally and either use a single V or an
 * ArrayList of V. Then take over where we do this in the code e.g. PrioritizedTicker.
 *
 * @author oskar
 */

public class MultiValueTable<K,V> {
    private Hashtable<K, Vector<V>> table;
    private int ies;

    public MultiValueTable() {
        this(16, 3);
    }

    public MultiValueTable(int initialSize) {
        this(initialSize, 3);
    }

    public MultiValueTable(int initialSize, int initialEntrySize) {
        table = new Hashtable<K, Vector<V>>(initialSize);
        ies = initialEntrySize;
    }

    public static <K, V> MultiValueTable<K, V> from (K[] keys, V[] values) {
      if (keys.length != values.length) {
        throw new IllegalArgumentException(String.format(
            "keys and values must contain the same number of values, but there are %d keys and %d values",
            keys.length,
            values.length));
      }
      MultiValueTable<K, V> table = new MultiValueTable<>();
      for (int i = 0; i < keys.length; i++) {
        table.put(keys[i], values[i]);
      }
      return table;
    }

    public void put(K key, V value) {
        synchronized (table) {
            Vector<V> v = table.get(key);
            if (v == null) {
                v = new Vector<V>(ies);
                table.put(key, v);
            }
            v.addElement(value);
        }
    }

    /**
     * Returns the first element for this key.
     */
    public V get(K key) {
        synchronized (table) {
            Vector<V> v = table.get(key);
            return (v == null ?
                    null :
                    v.firstElement());
        }
    }

    public boolean containsKey(K key) {
		synchronized (table) {
			return table.containsKey(key);
		}
    }

    public boolean containsElement(K key, V value) {
        synchronized (table) {
            Vector<V> v = table.get(key);
            return (v != null) && v.contains(value);
        }
    }

    /**
     * Users will have to handle synchronizing.
     */
    public Enumeration<V> getAll(K key) {
    	Vector<V> v;
		synchronized (table) {
			v = table.get(key);
		}
        return (v == null ?
                new EmptyEnumeration<V>() :
                v.elements());
    }

    /**
     * To be used in for(x : y).
     */
    public Iterable<V> iterateAll(K key) {
		synchronized (table) {
			return(table.get(key));
		}
    }

    public int countAll(K key) {
    	Vector<V> v;
		synchronized (table) {
			v = table.get(key);
		}
    	if(v != null)
        	return v.size();
        else
        	return 0;
    }

    public Object getSync(K key) {
		synchronized (table) {
			return table.get(key);
		}
    }

    public Object[] getArray(K key) {
        synchronized (table) {
            Vector<V> v = table.get(key);
            if (v == null)
                return null;
            else {
            	Object[] r = new Object[v.size()];
                v.copyInto(r);
                return r;
            }
        }
    }

    public void remove(K key) {
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

    public boolean removeElement(K key, V value) {
        synchronized (table) {
            Vector<V> v = table.get(key);
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

    public Enumeration<K> keys() {
		synchronized (table) {
			return table.keys();
		}
    }

    public Enumeration<V> elements() {
		synchronized (table) {
			if (table.isEmpty())
				return new EmptyEnumeration<V>();
			else
				return new MultiValueEnumeration();
		}
    }

  @Override
  public String toString() {
    return "[MultiValueTable table=" + table.toString() + "]";
  }


  private static class EmptyEnumeration<E> implements Enumeration<E> {
        @Override
        public final boolean hasMoreElements() {
            return false;
        }

        @Override
        public final E nextElement() {
            throw new NoSuchElementException();
        }
    }

    private class MultiValueEnumeration implements Enumeration<V> {
        private Enumeration<V> current;
        private Enumeration< Vector<V>> global;
        public MultiValueEnumeration() {
			synchronized (table) {
				global = table.elements();
			}
            current =  global.nextElement().elements();
            step();
        }

        public final void step() {
            while (!current.hasMoreElements() && global.hasMoreElements())
                current = global.nextElement().elements();
        }

        @Override
        public final boolean hasMoreElements() {
            return global.hasMoreElements(); // || current.hasMoreElements();
        }

        @Override
        public final V nextElement() {
            V o = current.nextElement();
            step();
            return o;
        }
    }

}
