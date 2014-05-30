/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.Bucket;

//A Bucket which does not support being stored to the database. E.g. SegmentedBCB.
public interface NotPersistentBucket extends Bucket {

    // No methods
}
