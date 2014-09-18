package freenet.crypt;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import freenet.support.io.ByteArrayRandomAccessThing;
import freenet.support.io.RandomAccessThing;
import freenet.support.io.RandomAccessThingTestBase;

public class EncryptedRandomAccessThingAltTest extends RandomAccessThingTestBase {
    
    private final static EncryptedRandomAccessThingType[] types = 
        EncryptedRandomAccessThingType.values();

    private final static MasterSecret secret = new MasterSecret();
    
    static{
        Security.addProvider(new BouncyCastleProvider());
    }
    
    private static final int[] TEST_LIST = new int[] { 0, 1, 32, 64, 32768, 1024*1024, 1024*1024+1 };
    
    public EncryptedRandomAccessThingAltTest() {
        super(TEST_LIST);
    }
    
    @Override
    protected RandomAccessThing construct(long size) throws IOException {
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing((int)(size+types[0].headerLen));
        try {
            return new EncryptedRandomAccessThing(types[0], barat, secret);
        } catch (GeneralSecurityException e) {
            throw new Error(e);
        }
    }

}
