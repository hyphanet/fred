package freenet.support.io;

public class TempBucketFactoryRAFPlaintextTest extends TempBucketFactoryRAFBase {

    @Override
    public boolean enableCrypto() {
        return false;
    }

}
