package freenet.support;

import java.util.Enumeration;
import java.util.NoSuchElementException;

/** We kept remaking this everywhere so wtf.
  * @author tavin
  */
public final class LimitedEnumeration<T> implements Enumeration<T> {
	private T next;

	public LimitedEnumeration() {
		next = null;
	}

	public LimitedEnumeration(T loner) {
		next = loner;
	}

	public final boolean hasMoreElements() {
		return next != null;
	}

	public final T nextElement() {
		if (next == null) throw new NoSuchElementException();
		try {
			return next;
		}
		finally {
			next = null;
		}
	}
}
