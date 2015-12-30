/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Security;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;

public class CryptByteBufferTest {
    private static final CryptByteBufferType[] cipherTypes = CryptByteBufferType.values();

    private static final String ivPlainText = "6bc1bee22e409f96e93d7e117393172a"
            + "ae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52ef"
            + "f69f2445df4f9b17ad2b417be66c3710";

    private static final String[] plainText =
        { "0123456789abcdef1123456789abcdef2123456789abcdef3123456789abcdef",
        "0123456789abcdef1123456789abcdef", 
        ivPlainText, ivPlainText, ivPlainText, ivPlainText};

    private static final byte[][] keys = 
        { Hex.decode("deadbeefcafebabe0123456789abcdefcafebabedeadbeefcafebabe01234567"),
        Hex.decode("deadbeefcafebabe0123456789abcdefcafebabedeadbeefcafebabe01234567"),
        Hex.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"),
        Hex.decode("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"),
        Hex.decode("8c123cffb0297a71ae8388109a6527dd"),
        Hex.decode("a63add96a3d5975e2dad2f904ff584a32920e8aa54263254161362d1fb785790")};
    private static final byte[][] ivs = 
        { null,
        null,
        Hex.decode("f0f1f2f3f4f5f6f7f8f9fafbfcfdfefff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
        Hex.decode("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
        Hex.decode("73c3c8df749084bb"),
        Hex.decode("7b471cf26ee479fb")};

    static{
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testSuccessfulRoundTripByteArray() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] decipheredtext = crypt.decryptCopy(crypt.encryptCopy(Hex.decode(plainText[i])));
            assertArrayEquals("CryptByteBufferType: "+type.name(), 
                    Hex.decode(plainText[i]), decipheredtext);
        }
    }
    
    @Test
    public void testRoundOneByte() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt1;
            CryptByteBuffer crypt2;
            
            if(!type.isStreamCipher) continue;

            if(ivs[i] == null){
                crypt1 = new CryptByteBuffer(type, keys[i]);
                crypt2 = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt1 = new CryptByteBuffer(type, keys[i], ivs[i]);
                crypt2 = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            
            byte[] origPlaintext = Hex.decode(plainText[i]);
            byte[] origCiphertext = crypt1.encryptCopy(origPlaintext);
            // Now encrypt one byte at a time.
            byte[] buf = origPlaintext.clone();
            for(int j=0;j<buf.length;j++) {
                crypt2.encrypt(buf, j, 1);
                assertEquals(buf[j], origCiphertext[j]);
            }
            for(int j=0;j<buf.length;j++) {
                crypt2.decrypt(buf, j, 1);
                assertEquals(buf[j], origPlaintext[j]);
            }
        }            
    }

    @Test
    public void testRoundRandomLengthBytes() throws GeneralSecurityException {
        Random random = new Random(0xAAAAAAAA);
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt1;
            CryptByteBuffer crypt2;
            
            if(!type.isStreamCipher) continue;

            if(ivs[i] == null){
                crypt1 = new CryptByteBuffer(type, keys[i]);
                crypt2 = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt1 = new CryptByteBuffer(type, keys[i], ivs[i]);
                crypt2 = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            
            byte[] origPlaintext = Hex.decode(plainText[i]);
            byte[] origCiphertext = crypt1.encryptCopy(origPlaintext);
            
            // Now encrypt one byte at a time.
            byte[] buf = origPlaintext.clone();
            int j=0;
            while(j < buf.length) {
                int r = buf.length - j;
                int copy = 1 + (r == 1 ? 0 : random.nextInt(r-1));
                crypt2.encrypt(buf, j, copy);
                j += copy;
            }
            assertArrayEquals(buf, origCiphertext);
            j = 0;
            while(j < buf.length) {
                int r = buf.length - j;
                int copy = 1 + (r == 1 ? 0 : random.nextInt(r-1));
                crypt2.decrypt(buf, j, copy);
                j += copy;
            }
            assertArrayEquals(buf, origPlaintext);
        }            
    }

    @Test
    public void testSuccessfulRoundTripInPlace() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] buffer = Hex.decode(plainText[i]);
            byte[] plaintextCopy = buffer.clone();
            crypt.encrypt(buffer, 0, buffer.length);
            assertTrue(!Arrays.equals(buffer, plaintextCopy));
            crypt.decrypt(buffer, 0, buffer.length);
            assertArrayEquals("CryptByteBufferType: "+type.name(), 
                    plaintextCopy, buffer);
        }
    }

    @Test
    public void testSuccessfulRoundTripInPlaceOffset() throws GeneralSecurityException {
        int header = 5;
        int footer = 5;
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] originalPlaintext = Hex.decode(plainText[i]);
            byte[] buffer = new byte[header+originalPlaintext.length+footer];
            byte[] copyBuffer = buffer.clone();
            System.arraycopy(originalPlaintext, 0, buffer, header, originalPlaintext.length);
            crypt.encrypt(buffer, footer, originalPlaintext.length);
            assertTrue(!Arrays.equals(buffer, copyBuffer));
            crypt.decrypt(buffer, footer, originalPlaintext.length);
            assertArrayEquals("CryptByteBufferType: "+type.name(), 
                    originalPlaintext, Arrays.copyOfRange(buffer, footer, footer+originalPlaintext.length));
        }
    }

    @Test
    public void testSuccessfulRoundTripOutOfPlaceOffset() throws GeneralSecurityException {
        int inHeader = 5;
        int inFooter = 5;
        int outHeader = 33;
        int outFooter = 33;
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] originalPlaintext = Hex.decode(plainText[i]);
            byte[] buffer = new byte[inHeader+originalPlaintext.length+inFooter];
            System.arraycopy(originalPlaintext, 0, buffer, inHeader, originalPlaintext.length);
            byte[] copyBuffer = buffer.clone();
            
            byte[] outBuffer = new byte[outHeader + originalPlaintext.length + outFooter];
            crypt.encrypt(buffer, inFooter, originalPlaintext.length, outBuffer, outHeader);
            assertTrue(Arrays.equals(buffer, copyBuffer));
            copyBuffer = outBuffer.clone();
            crypt.decrypt(outBuffer, outHeader, originalPlaintext.length, buffer, inFooter);
            assertTrue(Arrays.equals(copyBuffer, outBuffer));
            
            assertArrayEquals("CryptByteBufferType: "+type.name(), 
                    originalPlaintext, Arrays.copyOfRange(buffer, inFooter, inFooter+originalPlaintext.length));
        }
    }

    @Test
    public void testSuccessfulRoundTripByteArrayReset() throws GeneralSecurityException  {
            for(int i = 0; i < cipherTypes.length; i++){
                CryptByteBufferType type = cipherTypes[i];
                CryptByteBuffer crypt;
                byte[] originalData = Hex.decode(plainText[i]);
                int len = originalData.length;
                ByteBuffer plain = ByteBuffer.wrap(originalData);
                if(ivs[i] == null){
                    crypt = new CryptByteBuffer(type, keys[i]);
                } else {
                    crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
                }
                ByteBuffer ciphertext1 = crypt.encryptCopy(plain);
                ByteBuffer ciphertext2 = crypt.encryptCopy(plain);
                ByteBuffer ciphertext3 = crypt.encryptCopy(plain);
                assertEquals(ciphertext1.capacity(), len);
                assertEquals(ciphertext2.capacity(), len);
                assertEquals(ciphertext3.capacity(), len);
                assertEquals(ciphertext1.remaining(), len);
                assertEquals(ciphertext2.remaining(), len);
                assertEquals(ciphertext3.remaining(), len);
                
                if(type.isStreamCipher) {
                    // Once we have initialised the cipher, it is treated as a stream.
                    // Repeated encryption of the same data will return different ciphertext, 
                    // as it is treated as a later point in the stream.
                    assertNotEquals(ciphertext1, ciphertext2);
                    assertNotEquals(ciphertext1, ciphertext3);
                    assertNotEquals(ciphertext2, ciphertext3);
                }
                
                ByteBuffer decipheredtext1 = crypt.decryptCopy(ciphertext1);
                ByteBuffer decipheredtext2 = crypt.decryptCopy(ciphertext2);
                ByteBuffer decipheredtext3 = crypt.decryptCopy(ciphertext3);
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext1));
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext2));
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext3));
            }
    }
    
    private void assertNotEquals(Object o1, Object o2) {
        assertFalse(o1.equals(o2));
    }

    @Test
    public void testEncryptWrapByteBuffer() throws GeneralSecurityException {
        int header = 5;
        int footer = 5;
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt, crypt2;
            byte[] origPlaintext = Hex.decode(plainText[i]);
            byte[] buf = new byte[origPlaintext.length+header+footer];
            System.arraycopy(origPlaintext, 0, buf, header, origPlaintext.length);
            byte[] cloneBuf = buf.clone();
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
                crypt2 = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
                crypt2 = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            ByteBuffer plaintext = ByteBuffer.wrap(buf, header, origPlaintext.length);
            ByteBuffer ciphertext = crypt.encryptCopy(plaintext);
            assertTrue(Arrays.equals(buf, cloneBuf)); // Plaintext not modified.
            assertEquals(ciphertext.remaining(), origPlaintext.length);
            byte[] altCiphertext = crypt2.encryptCopy(origPlaintext);
            byte[] ciphertextBytes = new byte[origPlaintext.length];
            ciphertext.get(ciphertextBytes);
            ciphertext.position(0);
            assertTrue(Arrays.equals(altCiphertext, ciphertextBytes));
            ByteBuffer deciphered = crypt.decryptCopy(ciphertext);
            assertTrue(deciphered.equals(plaintext));
            byte[] data = new byte[origPlaintext.length];
            deciphered.get(data);
            assertTrue(Arrays.equals(data, origPlaintext));
        }
    }
    
    @Test
    public void testEncryptByteBufferToByteBuffer() throws GeneralSecurityException {
        int header = 5;
        int footer = 5;
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] origPlaintext = Hex.decode(plainText[i]);
            byte[] buf = new byte[origPlaintext.length+header+footer];
            System.arraycopy(origPlaintext, 0, buf, header, origPlaintext.length);
            byte[] cloneBuf = buf.clone();
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            ByteBuffer plaintext = ByteBuffer.wrap(buf, header, origPlaintext.length);
            byte[] ciphertextBuf = new byte[origPlaintext.length+header+footer];
            byte[] copyCiphertextBuf = ciphertextBuf.clone();
            ByteBuffer ciphertext = ByteBuffer.wrap(ciphertextBuf, header, origPlaintext.length);
            crypt.encrypt(plaintext, ciphertext);
            assertEquals(plaintext.position(), header+origPlaintext.length);
            assertEquals(ciphertext.position(), header+origPlaintext.length);
            assertTrue(Arrays.equals(buf, cloneBuf)); // Plaintext not modified.
            plaintext.position(header);
            ciphertext.position(header);
            assertTrue(!Arrays.equals(ciphertextBuf, copyCiphertextBuf));
            Arrays.fill(buf, (byte)0);
            assertFalse(Arrays.equals(buf, cloneBuf));
            crypt.decrypt(ciphertext, plaintext);
            assertTrue(Arrays.equals(buf, cloneBuf));
        }
    }

    @Test
    public void testEncryptByteBufferToByteBufferDirect() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] origPlaintext = Hex.decode(plainText[i]);
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            ByteBuffer plaintext = ByteBuffer.allocateDirect(origPlaintext.length);
            plaintext.put(origPlaintext);
            plaintext.position(0);
            ByteBuffer ciphertext = ByteBuffer.allocateDirect(origPlaintext.length);
            crypt.encrypt(plaintext, ciphertext);
            assertEquals(plaintext.position(), origPlaintext.length);
            assertEquals(ciphertext.position(), origPlaintext.length);
            plaintext.position(0);
            ciphertext.position(0);
            byte[] ciphertextCopy = new byte[origPlaintext.length];
            ciphertext.get(ciphertextCopy);
            ciphertext.position(0);
            assertFalse(Arrays.equals(origPlaintext, ciphertextCopy));
            crypt.decrypt(ciphertext, plaintext);
            assertEquals(plaintext.position(), origPlaintext.length);
            assertEquals(ciphertext.position(), origPlaintext.length);
            assertEquals(plaintext, ciphertext);
            plaintext.position(0);
            byte[] finalPlaintext = new byte[origPlaintext.length];
            plaintext.get(finalPlaintext);
            assertArrayEquals(finalPlaintext, origPlaintext);
        }
    }

    @Test
    public void testDecryptWrapByteBuffer() throws GeneralSecurityException {
        int header = 5;
        int footer = 5;
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] origPlaintext = Hex.decode(plainText[i]);
            byte[] buf = origPlaintext.clone();
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            ByteBuffer plaintext = ByteBuffer.wrap(buf);
            ByteBuffer ciphertext = crypt.encryptCopy(plaintext);
            assertTrue(Arrays.equals(buf, origPlaintext)); // Plaintext not modified.
            assertEquals(ciphertext.remaining(), origPlaintext.length);
            byte[] decryptBuf = new byte[header+origPlaintext.length+footer];
            ciphertext.get(decryptBuf, header, origPlaintext.length);
            byte[] copyOfDecryptBuf = decryptBuf.clone();
            ByteBuffer toDecipher = ByteBuffer.wrap(decryptBuf, header, origPlaintext.length);
            ByteBuffer deciphered = crypt.decryptCopy(toDecipher);
            assertTrue(Arrays.equals(decryptBuf, copyOfDecryptBuf));
            assertTrue(deciphered.equals(plaintext));
            byte[] data = new byte[origPlaintext.length];
            deciphered.get(data);
            assertTrue(Arrays.equals(data, origPlaintext));
        }
    }

    @Test
    public void testEncryptDirectByteBuffer() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] origPlaintext = Hex.decode(plainText[i]);
            ByteBuffer plaintext = ByteBuffer.allocateDirect(origPlaintext.length);
            plaintext.clear();
            plaintext.put(origPlaintext);
            plaintext.clear();
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            ByteBuffer ciphertext = crypt.encryptCopy(plaintext);
            plaintext.clear();
            byte[] copyPlaintext = new byte[origPlaintext.length];
            plaintext.get(copyPlaintext);
            assertTrue(Arrays.equals(origPlaintext, copyPlaintext)); // Plaintext not modified.
            plaintext.clear();
            assertEquals(ciphertext.remaining(), origPlaintext.length);
            ByteBuffer deciphered = crypt.decryptCopy(ciphertext);
            assertTrue(deciphered.equals(plaintext));
            byte[] data = new byte[origPlaintext.length];
            deciphered.get(data);
            assertTrue(Arrays.equals(data, origPlaintext));
        }
    }

    @Test
    public void testSuccessfulRoundTripByteArrayNewInstance() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] plain = Hex.decode(plainText[i]);
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] ciphertext = crypt.encryptCopy(plain);
            byte[] ciphertext2 = crypt.encryptCopy(plain);
            byte[] ciphertext3 = crypt.encryptCopy(plain);
            
            if(type.isStreamCipher) {
                assertFalse(Arrays.equals(ciphertext, ciphertext2));
                assertFalse(Arrays.equals(ciphertext, ciphertext3));
                assertFalse(Arrays.equals(ciphertext2, ciphertext3));
            }

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] decipheredtext = crypt.decryptCopy(ciphertext);
            assertArrayEquals("CryptByteBufferType: "+type.name(), plain, decipheredtext);
            decipheredtext = crypt.decryptCopy(ciphertext2);
            assertArrayEquals("CryptByteBufferType2: "+type.name(), plain, decipheredtext);
            decipheredtext = crypt.decryptCopy(ciphertext3);
            assertArrayEquals("CryptByteBufferType3: "+type.name(), plain, decipheredtext);
        }
    }

//    @Test
//    public void testSuccessfulRoundTripBitSet() throws GeneralSecurityException {
//        for(int i = 0; i < cipherTypes.length; i++){
//            CryptByteBufferType type = cipherTypes[i];
//            CryptByteBuffer crypt;
//            byte[] plain = Hex.decode(plainText[i]);
//            if(ivs[i] == null){
//                crypt = new CryptByteBuffer(type, keys[i]);
//            } else {
//                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
//            }
//            BitSet plaintext = BitSet.valueOf(plain);
//            BitSet ciphertext = crypt.encrypt(plaintext);
//            BitSet decipheredtext = crypt.decrypt(ciphertext);
//            assertTrue("CryptByteBufferType: "+type.name(), plaintext.equals(decipheredtext));
//        }
//    }

    @Test
    public void testEncryptByteArrayNullInput() throws GeneralSecurityException{
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            byte[] nullArray = null;
            try{
                crypt.encryptCopy(nullArray);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

    // FIXME
//    @Test
//    public void testEncryptBitSetNullInput() throws GeneralSecurityException{
//        for(int i = 0; i < cipherTypes.length; i++){
//            CryptByteBufferType type = cipherTypes[i];
//            CryptByteBuffer crypt;
//            if(ivs[i] == null){
//                crypt = new CryptByteBuffer(type, keys[i]);
//            } else {
//                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
//            }
//
//            BitSet nullSet = null;
//            try{
//                crypt.encrypt(nullSet);
//                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
//            }catch(NullPointerException e){}
//        }
//    }

    @Test
    public void testEncryptByteArrayIntIntNullInput() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            byte[] nullArray = null;
            try{
                crypt.encryptCopy(nullArray, 0, plainText[i].length());
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "NullPointerException");
            }catch(IllegalArgumentException e) {
            }catch(NullPointerException e){}
        } 
    }

    @Test
    public void testEncryptByteArrayIntIntOffsetOutOfBounds() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            try{
                crypt.encryptCopy(Hex.decode(plainText[i]), -3, plainText[i].length()-3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException e) {
            }catch(IndexOutOfBoundsException e){
            }
        } 
    }

    @Test
    public void testEncryptByteArrayIntIntLengthOutOfBounds() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            try{
                crypt.encryptCopy(Hex.decode(plainText[i]), 0, plainText[i].length()+3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException e) {
            } catch(IndexOutOfBoundsException e){}
        } 
    }

    @Test
    public void testDecryptByteArrayNullInput() throws GeneralSecurityException{
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            byte[] nullArray = null;
            try{
                crypt.decryptCopy(nullArray);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

    // FIXME
//    @Test
//    public void testDecryptBitSetNullInput() throws GeneralSecurityException{
//        for(int i = 0; i < cipherTypes.length; i++){
//            CryptByteBufferType type = cipherTypes[i];
//            CryptByteBuffer crypt;
//            if(ivs[i] == null){
//                crypt = new CryptByteBuffer(type, keys[i]);
//            } else {
//                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
//            }
//
//            BitSet nullSet = null;
//            try{
//                crypt.decrypt(nullSet);
//                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
//            }catch(NullPointerException e){}
//        }
//    }

    @Test
    public void testDecryptByteArrayIntIntNullInput() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            byte[] nullArray = null;
            try{
                crypt.decryptCopy(nullArray, 0, plainText[i].length());
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "NullPointerException");
            }catch(NullPointerException e) {
            } catch(IllegalArgumentException e){
            }
        } 
    }

    @Test
    public void testDecryptByteArrayIntIntOffsetOutOfBounds() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            try{
                crypt.decryptCopy(Hex.decode(plainText[i]), -3, plainText[i].length()-3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException e) {
            } catch (IndexOutOfBoundsException e){
            }
        } 
    }

    @Test
    public void testDecryptByteArrayIntIntLengthOutOfBounds() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            try{
                crypt.decryptCopy(Hex.decode(plainText[i]), 0, plainText[i].length()+3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException e) {
            } catch (IndexOutOfBoundsException e){
            }
        } 
    }

    @Test
    public void testGetIV() throws InvalidKeyException, InvalidAlgorithmParameterException {
        int i = 4;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i], ivs[i]);
        assertArrayEquals(crypt.getIV().getIV(), ivs[i]);
    }

    @Test
    public void testSetIVIvParameterSpec() 
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        int i = 4;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i], ivs[i]);
        crypt.genIV();
        crypt.setIV(new IvParameterSpec(ivs[i]));
        assertArrayEquals(ivs[i], crypt.getIV().getIV());
    }

    @Test 
    public void testSetIVIvParameterSpecNullInput() 
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        IvParameterSpec nullInput = null;
        int i = 4;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i], ivs[i]);
        try{
            crypt.setIV(nullInput);
            fail("Expected InvalidAlgorithmParameterException");
        } catch (InvalidAlgorithmParameterException e){}
    }

    @Test (expected = UnsupportedTypeException.class)
    public void testSetIVIvParameterSpecUnsupportedTypeException() throws GeneralSecurityException {
        int i = 0;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i]);
        crypt.setIV(new IvParameterSpec(ivs[4]));
        fail("Expected UnsupportedTypeException");
    }

    @Test
    public void testGenIV() throws InvalidKeyException, InvalidAlgorithmParameterException {
        int i = 4;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i], ivs[i]);
        assertNotNull(crypt.genIV());
    }

    @Test
    public void testGenIVLength() throws InvalidKeyException, InvalidAlgorithmParameterException {
        int i = 4;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i], ivs[i]);
        assertEquals(crypt.genIV().getIV().length, cipherTypes[i].ivSize.intValue());
    }

    @Test (expected = UnsupportedTypeException.class)
    public void testGenIVUnsupportedTypeException() throws GeneralSecurityException {
        int i = 1;
        CryptByteBuffer crypt = new CryptByteBuffer(cipherTypes[i], keys[i]);
        crypt.genIV();
        fail("Expected UnsupportedTypeException");
    }
    
    @Test
    public void testOverlappingEncode() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt, crypt2;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
                crypt2 = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
                crypt2 = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] originalPlaintext = Hex.decode(plainText[i]);
            byte[] originalCiphertext = crypt2.encryptCopy(originalPlaintext);
            byte[] buf = new byte[originalPlaintext.length+1];
            System.arraycopy(originalPlaintext, 0, buf, 0, originalPlaintext.length);
            crypt.encrypt(buf, 0, originalPlaintext.length, buf, 1);
            assertArrayEquals(originalCiphertext, Arrays.copyOfRange(buf, 1, buf.length));
        }
    }

    @Test
    public void testOverlappingDecode() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt, crypt2;

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
                crypt2 = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
                crypt2 = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] originalPlaintext = Hex.decode(plainText[i]);
            byte[] originalCiphertext = crypt2.encryptCopy(originalPlaintext);
            byte[] buf = new byte[originalPlaintext.length+1];
            System.arraycopy(originalCiphertext, 0, buf, 0, originalCiphertext.length);
            crypt.decrypt(buf, 0, originalCiphertext.length, buf, 1);
            assertArrayEquals(originalPlaintext, Arrays.copyOfRange(buf, 1, buf.length));
        }
    }

}
