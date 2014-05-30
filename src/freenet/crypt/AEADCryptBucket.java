/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.crypt;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.node.NodeStarter;

import freenet.support.api.Bucket;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Arrays;

/**
 * Encrypted and authenticated Bucket implementation using AES cipher and OCB mode. Warning:
 * Avoid using Closer.close() on InputStream's opened on this Bucket. The MAC is only checked when
 * the end of the bucket is reached, which may be in read() or may be in close().
 * @author toad
 */
public class AEADCryptBucket implements Bucket {
    static final int OVERHEAD = AEADOutputStream.AES_OVERHEAD;
    private final Bucket underlying;
    private final byte[] key;
    private boolean readOnly;

    public AEADCryptBucket(Bucket underlying, byte[] key) {
        this.underlying = underlying;
        this.key = Arrays.copyOf(key, key.length);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        synchronized (this) {
            if (readOnly) {
                throw new IOException("Read only");
            }
        }

        OutputStream os = underlying.getOutputStream();

        return AEADOutputStream.createAES(os, key, NodeStarter.getGlobalSecureRandom());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream is = underlying.getInputStream();

        return AEADInputStream.createAES(is, key);
    }

    @Override
    public String getName() {
        return "AEADEncrypted:" + underlying.getName();
    }

    @Override
    public long size() {
        return underlying.size() - OVERHEAD;
    }

    @Override
    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public synchronized void setReadOnly() {
        readOnly = true;
    }

    @Override
    public void free() {
        underlying.free();
    }

    @Override
    public void storeTo(ObjectContainer container) {
        underlying.storeTo(container);
        container.store(this);
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        underlying.removeFrom(container);
        container.delete(this);
    }

    @Override
    public Bucket createShadow() {
        Bucket undershadow = underlying.createShadow();
        AEADCryptBucket ret = new AEADCryptBucket(undershadow, key);

        ret.setReadOnly();

        return ret;
    }
}
