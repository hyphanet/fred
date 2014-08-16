package freenet.crypt;

import static org.junit.Assert.*;

import java.io.IOException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

import freenet.support.io.ByteArrayRandomAccessThing;

public class EncryptedRandomAccessThingTest {
    private final static EncryptedRandomAccessThingType[] types = 
            EncryptedRandomAccessThingType.values();
    private final static byte[] message = "message".getBytes();
    private final static MasterSecret secret = new MasterSecret();
    
    static{
        Security.addProvider(new BouncyCastleProvider());
    }
    
    @Test
    public void testSuccesfulRoundTrip() throws IOException{
        for(EncryptedRandomAccessThingType type: types){
            byte[] bytes = new byte[100];
            ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
            EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(type, barat, secret);
            erat.pwrite(0, message, 0, message.length);
//            erat.close();
//            ByteArrayRandomAccessThing barat2 = new ByteArrayRandomAccessThing(bytes);
//            EncryptedRandomAccessThing erat2 = new EncryptedRandomAccessThing(type, barat2, secret);
            byte[] result = new byte[message.length];
            erat.pread(0, result, 0, result.length);
            assertArrayEquals(message, result);
        }
    }
    
    @Test
    public void testEncryptedRandomAccessThing() {
        fail("Not yet implemented");
    }

    @Test
    public void testSize() {
        fail("Not yet implemented");
    }

    @Test
    public void testPread() {
        fail("Not yet implemented");
    }

    @Test
    public void testPwrite() {
        fail("Not yet implemented");
    }

    @Test
    public void testClose() {
        fail("Not yet implemented");
    }

}
