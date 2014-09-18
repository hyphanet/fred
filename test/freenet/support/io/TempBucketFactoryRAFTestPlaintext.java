package freenet.support.io;

public class TempBucketFactoryRAFTestPlaintext extends TempBucketFactoryRAFTest {

    @Override
    public boolean enableCrypto() {
        return false;
    }

}
