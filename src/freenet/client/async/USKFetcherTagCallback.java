/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

public interface USKFetcherTagCallback extends USKFetcherCallback {
    public void setTag(USKFetcherTag tag, ObjectContainer container, ClientContext context);
}
