/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.support.io;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.support.api.Bucket;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class NoFreeBucket implements Bucket {
    final Bucket proxy;

    public NoFreeBucket(Bucket orig) {
        proxy = orig;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return proxy.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return proxy.getInputStream();
    }

    @Override
    public String getName() {
        return proxy.getName();
    }

    @Override
    public long size() {
        return proxy.size();
    }

    @Override
    public boolean isReadOnly() {
        return proxy.isReadOnly();
    }

    @Override
    public void setReadOnly() {
        proxy.setReadOnly();
    }

    @Override
    public void free() {

        // Do nothing.
    }

    @Override
    public void storeTo(ObjectContainer container) {
        container.store(this);
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        container.delete(this);
    }

    @Override
    public Bucket createShadow() {
        return proxy.createShadow();
    }
}
