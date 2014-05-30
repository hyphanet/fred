/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

//~--- JDK imports ------------------------------------------------------------

import java.util.Enumeration;
import java.util.NoSuchElementException;

/**
 * We kept remaking this everywhere so wtf.
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

    @Override
    public final boolean hasMoreElements() {
        return next != null;
    }

    @Override
    public final T nextElement() {
        if (next == null) {
            throw new NoSuchElementException();
        }

        try {
            return next;
        } finally {
            next = null;
        }
    }
}
