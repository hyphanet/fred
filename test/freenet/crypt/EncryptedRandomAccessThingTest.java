/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import freenet.support.io.ByteArrayRandomAccessThing;

public class EncryptedRandomAccessThingTest {
    private final static EncryptedRandomAccessThingType[] types = 
            EncryptedRandomAccessThingType.values();
    private final static byte[] message = "message".getBytes();
    private final static MasterSecret secret = new MasterSecret();
    private final static long falseMagic = 0x2c158a6c8882ffd3L;
    
    static{
        Security.addProvider(new BouncyCastleProvider());
    }
    
    @Rule public ExpectedException thrown= ExpectedException.none();
    
    @Test
    public void testSuccesfulRoundTrip() throws IOException, GeneralSecurityException{
        for(EncryptedRandomAccessThingType type: types){
            byte[] bytes = new byte[100];
            ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
            EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(type, barat, secret);
            erat.pwrite(0, message, 0, message.length);
            byte[] result = new byte[message.length];
            erat.pread(0, result, 0, result.length);
            erat.close();
            assertArrayEquals(message, result);
        }
    }
    
    @Test
    public void testSuccesfulRoundTripReadHeader() throws IOException, GeneralSecurityException{
        for(EncryptedRandomAccessThingType type: types){
            byte[] bytes = new byte[100];
            ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
            EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(type, barat, secret);
            erat.pwrite(0, message, 0, message.length);
            erat.close();
            ByteArrayRandomAccessThing barat2 = new ByteArrayRandomAccessThing(bytes);
            EncryptedRandomAccessThing erat2 = new EncryptedRandomAccessThing(type, barat2, secret);
            byte[] result = new byte[message.length];
            erat2.pread(0, result, 0, result.length);
            erat2.close();
            assertArrayEquals(message, result);
        }
    }
    
    @Test
    public void testWrongERATType() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        ByteArrayRandomAccessThing barat2 = new ByteArrayRandomAccessThing(bytes);
        thrown.expect(IOException.class);
        thrown.expectMessage("Version of the underlying RandomAccessThing is incompatible with "
                + "this ERATType");
        EncryptedRandomAccessThing erat2 = new EncryptedRandomAccessThing(types[1], barat2, 
                secret);
    }
    
    @Test
    public void testUnderlyingRandomAccessThingTooSmall() 
            throws GeneralSecurityException, IOException {
        byte[] bytes = new byte[10];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        thrown.expect(IOException.class);
        thrown.expectMessage("Underlying RandomAccessThing is not long enough to include the "
                + "footer.");
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
    }
    
    @Test
    public void testWrongMagic() throws IOException, GeneralSecurityException{
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        ByteArrayRandomAccessThing barat2 = new ByteArrayRandomAccessThing(bytes);
        byte[] magic = ByteBuffer.allocate(8).putLong(falseMagic).array();
        barat2.pwrite(barat2.size()-8, magic, 0, 8);
        thrown.expect(IOException.class);
        thrown.expectMessage("This is not an EncryptedRandomAccessThing!");
        EncryptedRandomAccessThing erat2 = new EncryptedRandomAccessThing(types[0], barat2, secret);
    }
    
    @Test
    public void testWrongMasterSecret() throws IOException, GeneralSecurityException{
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        ByteArrayRandomAccessThing barat2 = new ByteArrayRandomAccessThing(bytes);
        thrown.expect(GeneralSecurityException.class);
        thrown.expectMessage("Mac is incorrect");
        EncryptedRandomAccessThing erat2 = new EncryptedRandomAccessThing(types[0], barat2, 
                new MasterSecret());
    }
    
    @Test (expected = NullPointerException.class)
    public void testEncryptedRandomAccessThingNullInput1() 
            throws GeneralSecurityException, IOException {
        byte[] bytes = new byte[10];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(null, barat, secret);
    }
    
    @Test (expected = NullPointerException.class)
    public void testEncryptedRandomAccessThingNullByteArray() 
            throws GeneralSecurityException, IOException {
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(null);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
    }
    
    @Test (expected = NullPointerException.class)
    public void testEncryptedRandomAccessThingNullBARAT() 
            throws GeneralSecurityException, IOException {
        ByteArrayRandomAccessThing barat = null;
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
    }
    
    @Test (expected = NullPointerException.class)
    public void testEncryptedRandomAccessThingNullInput3() 
            throws GeneralSecurityException, IOException {
        byte[] bytes = new byte[10];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, null);
    }

    @Test
    public void testSize() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        assertEquals(erat.size(), barat.size()-types[0].footerLen);
    }

    @Test
    public void testPreadFileOffsetTooSmall() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        byte[] result = new byte[20];
        thrown.expect(IOException.class);
        thrown.expectMessage("Cannot read before zero");
        erat.pread(-1, result, 0, 20);
    }
    
    @Test
    public void testPreadFileOffsetTooBig() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        int len = 20;
        byte[] result = new byte[len];
        int offset = 100;
        thrown.expect(IOException.class);
        thrown.expectMessage("Cannot read after end: trying to read from "+offset+" to "+
                (offset+len)+" on block length "+erat.size());
        erat.pread(offset, result, 0, len);
    }
    
    @Test
    public void testPwriteFileOffsetTooSmall() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        byte[] result = new byte[20];
        thrown.expect(IOException.class);
        thrown.expectMessage("Cannot read before zero");
        erat.pwrite(-1, result, 0, 20);
    }
    
    @Test
    public void testPwirteFileOffsetTooBig() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        int len = 20;
        byte[] result = new byte[len];
        int offset = 100;
        thrown.expect(IOException.class);
        thrown.expectMessage("Cannot write after end: trying to write from "+offset+" to "+
                (offset+len)+" on block length "+erat.size());
        erat.pwrite(offset, result, 0, len);
    }

    @Test
    public void testClose() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        erat.close();
    }

    @Test
    public void testClosePread() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        byte[] result = new byte[20];
        thrown.expect(IOException.class);
        thrown.expectMessage("This RandomAccessThing has already been closed. It can no longer"
                    + " be read from.");
        erat.pread(0, result, 0, 20);
    }

    @Test
    public void testClosePwrite() throws IOException, GeneralSecurityException {
        byte[] bytes = new byte[100];
        ByteArrayRandomAccessThing barat = new ByteArrayRandomAccessThing(bytes);
        EncryptedRandomAccessThing erat = new EncryptedRandomAccessThing(types[0], barat, secret);
        erat.close();
        byte[] result = new byte[20];
        thrown.expect(IOException.class);
        thrown.expectMessage("This RandomAccessThing has already been closed. It can no longer"
                    + " be written to.");
        erat.pwrite(0, result, 0, 20);
    }

}
