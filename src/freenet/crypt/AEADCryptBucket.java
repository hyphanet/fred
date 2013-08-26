package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.support.Logger;
import freenet.support.api.Bucket;

/** Does not support persistence because of the need for a SecureRandom. FIXME we will need to
 * give it a ClientContext on deserialisation/activation somehow??? */
public class AEADCryptBucket implements Bucket {
    
    private final Bucket underlying;
    private final byte[] key;
    private final SecureRandom random;
    private boolean readOnly;
    private static final int OVERHEAD = AEADOutputStream.AES_OVERHEAD;
    
    public AEADCryptBucket(Bucket underlying, byte[] key, SecureRandom random) {
        this.underlying = underlying;
        this.key = Arrays.copyOf(key, key.length);
        this.random = random;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        synchronized(this) {
            if(readOnly)
                throw new IOException("Read only");
        }
        OutputStream os = underlying.getOutputStream();
        return AEADOutputStream.createAES(os, key, random);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        InputStream is = underlying.getInputStream();
        return AEADInputStream.createAES(is, key);
    }

    @Override
    public String getName() {
        return "AEADEncrypted:"+underlying.getName();
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
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        throw new UnsupportedOperationException();
    }
    
    public boolean objectCanNew(ObjectContainer container) {
        Logger.error(this, "Not storing AEADCryptBucket in database", new Exception("error"));
        return false;
    }

    @Override
    public Bucket createShadow() {
        Bucket undershadow = underlying.createShadow();
        AEADCryptBucket ret = new AEADCryptBucket(undershadow, key, random);
        ret.setReadOnly();
        return ret;
    }

}
