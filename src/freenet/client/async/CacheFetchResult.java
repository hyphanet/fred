/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.ClientMetadata;
import freenet.client.FetchResult;

import freenet.support.api.Bucket;

public class CacheFetchResult extends FetchResult {
    public final boolean alreadyFiltered;

    public CacheFetchResult(ClientMetadata dm, Bucket fetched, boolean alreadyFiltered) {
        super(dm, fetched);
        this.alreadyFiltered = alreadyFiltered;
    }
}
