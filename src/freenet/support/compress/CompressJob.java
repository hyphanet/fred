/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.compress;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.InsertException;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientPutState;

public interface CompressJob {
    public abstract void tryCompress(ClientContext context) throws InsertException;

    public abstract void onFailure(InsertException e, ClientPutState c, ClientContext context);
}
