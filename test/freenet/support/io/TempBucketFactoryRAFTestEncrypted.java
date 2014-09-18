package freenet.support.io;

public class TempBucketFactoryRAFTestEncrypted extends TempBucketFactoryRAFTest {

    @Override
    public boolean enableCrypto() {
        return true;
    }

}
