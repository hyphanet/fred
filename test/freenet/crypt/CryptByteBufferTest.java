/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Security;
import java.util.BitSet;

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
            ByteBuffer ciphertext = crypt.encrypt(Hex.decode(plainText[i]));

            byte[] decipheredtext = crypt.decrypt(ciphertext).array();
            assertArrayEquals("CryptByteBufferType: "+type.name(), 
                    Hex.decode(plainText[i]), decipheredtext);
        }
    }

    @Test
    public void testSuccessfulRoundTripByteArrayReset() throws GeneralSecurityException  {
            for(int i = 0; i < cipherTypes.length; i++){
                CryptByteBufferType type = cipherTypes[i];
                CryptByteBuffer crypt;
                ByteBuffer plain = ByteBuffer.wrap(Hex.decode(plainText[i]));
                if(ivs[i] == null){
                    crypt = new CryptByteBuffer(type, keys[i]);
                } else {
                    crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
                }
                ByteBuffer ciphertext1 = crypt.encrypt(plain);
                ByteBuffer ciphertext2 = crypt.encrypt(plain);
                ByteBuffer ciphertext3 = crypt.encrypt(plain);

                ByteBuffer decipheredtext2 = crypt.decrypt(ciphertext2);
                ByteBuffer decipheredtext1 = crypt.decrypt(ciphertext1);
                ByteBuffer decipheredtext3 = crypt.decrypt(ciphertext3);
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext1));
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext2));
                assertTrue("CryptByteBufferType: "+type.name(), plain.equals(decipheredtext3));
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
            crypt.encrypt(plain);
            ByteBuffer ciphertext = crypt.encrypt(plain);
            crypt.encrypt(plain);

            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            byte[] decipheredtext = crypt.decrypt(ciphertext).array();
            assertArrayEquals("CryptByteBufferType: "+type.name(), plain, decipheredtext);
        }
    }

    @Test
    public void testSuccessfulRoundTripBitSet() throws GeneralSecurityException {
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            byte[] plain = Hex.decode(plainText[i]);
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }
            BitSet plaintext = BitSet.valueOf(plain);
            BitSet ciphertext = crypt.encrypt(plaintext);
            BitSet decipheredtext = crypt.decrypt(ciphertext);
            assertTrue("CryptByteBufferType: "+type.name(), plaintext.equals(decipheredtext));
        }
    }

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
                crypt.encrypt(nullArray);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

    @Test
    public void testEncryptBitSetNullInput() throws GeneralSecurityException{
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            BitSet nullSet = null;
            try{
                crypt.encrypt(nullSet);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

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
                crypt.encrypt(nullArray, 0, plainText[i].length());
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "NullPointerException");
            }catch(IllegalArgumentException | NullPointerException e){}
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
                crypt.encrypt(Hex.decode(plainText[i]), -3, plainText[i].length()-3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException | IndexOutOfBoundsException e){}
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
                crypt.encrypt(Hex.decode(plainText[i]), 0, plainText[i].length()+3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException | IndexOutOfBoundsException e){}
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
                crypt.decrypt(nullArray);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

    @Test
    public void testDecryptBitSetNullInput() throws GeneralSecurityException{
        for(int i = 0; i < cipherTypes.length; i++){
            CryptByteBufferType type = cipherTypes[i];
            CryptByteBuffer crypt;
            if(ivs[i] == null){
                crypt = new CryptByteBuffer(type, keys[i]);
            } else {
                crypt = new CryptByteBuffer(type, keys[i], ivs[i]);
            }

            BitSet nullSet = null;
            try{
                crypt.decrypt(nullSet);
                fail("CryptByteBufferType: "+type.name()+": Expected NullPointerException");
            }catch(NullPointerException e){}
        }
    }

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
                crypt.decrypt(nullArray, 0, plainText[i].length());
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "NullPointerException");
            }catch(NullPointerException | IllegalArgumentException e){}
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
                crypt.decrypt(Hex.decode(plainText[i]), -3, plainText[i].length()-3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException | IndexOutOfBoundsException e){}
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
                crypt.decrypt(Hex.decode(plainText[i]), 0, plainText[i].length()+3);
                fail("CryptByteBufferType: "+type.name()+": Expected IllegalArgumentException or "
                        + "ArrayIndexOutOfBoundsException");
            }catch(IllegalArgumentException | IndexOutOfBoundsException e){}
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

}
