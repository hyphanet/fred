package freenet.support.io;

public class TempBucketFactoryRAFEncryptedTest extends TempBucketFactoryRAFBase {

    @Override
    public boolean enableCrypto() {
        return true;
    }

}
