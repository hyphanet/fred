/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;

import freenet.support.api.Bucket;

/**
 * An interface wich has to be implemented by FECJob submitters
 *
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 * WARNING: the callback is expected to release the thread !
 */
public interface FECCallback {

    /**
     * The implementor MUST copy the data manually from the arrays on the FECJob, because
     * db4o persists arrays as inline values, so WE CANNOT UPDATE THE ARRAY!!
     * @param container
     * @param context
     * @param job
     */
    public void onEncodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets,
                                 Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks);

    public void onDecodedSegment(ObjectContainer container, ClientContext context, FECJob job, Bucket[] dataBuckets,
                                 Bucket[] checkBuckets, SplitfileBlock[] dataBlocks, SplitfileBlock[] checkBlocks);

    /** Something broke. */
    public void onFailed(Throwable t, ObjectContainer container, ClientContext context);
}
