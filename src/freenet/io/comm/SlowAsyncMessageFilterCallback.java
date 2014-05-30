/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.io.comm;

/** AsyncMessageFilterCallback where the callbacks may do things that take significant time. */
public interface SlowAsyncMessageFilterCallback extends AsyncMessageFilterCallback {
    public int getPriority();
}
