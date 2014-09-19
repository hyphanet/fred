package freenet.support.io;

import java.io.IOException;
import java.security.GeneralSecurityException;

import freenet.crypt.EncryptedRandomAccessThing;
import freenet.crypt.MasterSecret;
import freenet.support.Logger;
import freenet.support.api.LockableRandomAccessThing;
import freenet.support.api.LockableRandomAccessThingFactory;

/** Wraps another RandomAccessThingFactory to enable encryption if currently turned on. */
public class MaybeEncryptedRandomAccessThingFactory implements LockableRandomAccessThingFactory {
    
    public MaybeEncryptedRandomAccessThingFactory(LockableRandomAccessThingFactory factory, boolean encrypt) {
        this.factory = factory;
        this.reallyEncrypt = encrypt;
    }
    
    private final LockableRandomAccessThingFactory factory;
    private volatile boolean reallyEncrypt;
    private MasterSecret secret;
    
    private static volatile boolean logMINOR;
    static {
        Logger.registerClass(MaybeEncryptedRandomAccessThingFactory.class);
    }

    @Override
    public LockableRandomAccessThing makeRAF(long size) throws IOException {
        long realSize = size;
        long paddedSize = size;
        MasterSecret secret = null;
        synchronized(this) {
            if(reallyEncrypt && this.secret != null) {
                secret = this.secret;
                realSize += TempBucketFactory.CRYPT_TYPE.headerLen;
                paddedSize = PaddedEphemerallyEncryptedBucket.paddedLength(realSize, PaddedEphemerallyEncryptedBucket.MIN_PADDED_SIZE);
                if(logMINOR) Logger.minor(this, "Encrypting and padding "+size+" to "+paddedSize);
            }
        }
        LockableRandomAccessThing raf = factory.makeRAF(paddedSize);
        if(secret != null) {
            if(realSize != paddedSize)
                raf = new PaddedRandomAccessThing(raf, realSize);
            try {
                raf = new EncryptedRandomAccessThing(TempBucketFactory.CRYPT_TYPE, raf, secret, true);
            } catch (GeneralSecurityException e) {
                Logger.error(this, "Cannot create encrypted tempfile: "+e, e);
            }
        }
        return raf;
    }

    @Override
    public LockableRandomAccessThing makeRAF(byte[] initialContents, int offset, int size,
            boolean readOnly) throws IOException {
        boolean reallyEncrypt = false;
        synchronized(this) {
            reallyEncrypt = this.reallyEncrypt;
        }
        if(reallyEncrypt) {
            // FIXME do the encryption in memory? Test it ...
            LockableRandomAccessThing ret = makeRAF(size);
            ret.pwrite(0, initialContents, offset, size);
            if(readOnly) ret = new ReadOnlyRandomAccessThing(ret);
            return ret;
        } else {
            return factory.makeRAF(initialContents, offset, size, readOnly);
        }
    }
    
    public void setMasterSecret(MasterSecret secret) {
        synchronized(this) {
            this.secret = secret;
        }
    }
    
    public void setEncryption(boolean value) {
        synchronized(this) {
            reallyEncrypt = value;
        }
        if(factory instanceof PooledFileRandomAccessThingFactory)
            ((PooledFileRandomAccessThingFactory)factory).enableCrypto(value);
    }
    
}
