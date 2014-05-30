/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;

import freenet.support.api.Bucket;

public interface DownloadCache {
    public CacheFetchResult lookupInstant(FreenetURI key, boolean noFilter, boolean mustCopy, Bucket preferred);

    public CacheFetchResult lookup(FreenetURI key, boolean noFilter, ClientContext context, ObjectContainer container,
                                   boolean mustCopy, Bucket preferred);
}
