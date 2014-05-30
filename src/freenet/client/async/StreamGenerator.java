/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.OutputStream;

/** Writes an underlying data structure to an output stream. */
public interface StreamGenerator {

    /**
     * Writes the data.
     * @param os Stream to which the data will be written
     * @param container
     * @param context
     * @throws IOException
     */
    public void writeTo(OutputStream os, ObjectContainer container, ClientContext context) throws IOException;

    /**
     * @return The size of the underlying structure
     */
    public long size();
}
