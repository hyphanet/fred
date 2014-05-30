/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.client.Metadata;

public class DumperSnoopMetadata implements SnoopMetadata {
    @Override
    public boolean snoopMetadata(Metadata meta, ObjectContainer container, ClientContext context) {
        System.err.print(meta.dump());

        return false;
    }
}
