/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support;

public class ExceptionWrapper {
    private Exception e;

    public synchronized Exception get() {
        return e;
    }

    public synchronized void set(Exception e) {
        this.e = e;
    }
}
