package freenet.crypt;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.SecretKey;

import org.bouncycastle.util.encoders.Hex;

import freenet.support.Logger;
import junit.framework.TestCase;

public class KeyGenUtilsTest extends TestCase {
	private static final int trueLength = 16;
	private static final int falseLength = -1;
	private static final KeyType[] keyTypes = KeyType.values();
	private static final byte[][] trueSecretKeys = {
			Hex.decode("20e86dc31ebf2c0e37670e30f8f45c57"),
			Hex.decode("8c6c2e0a60b3b73e9dbef076b68b686bacc9d20081e8822725d14b10b5034f48"),
			Hex.decode("33a4a38b71c8e350d3a98357d1bc9ecd"),
			Hex.decode("be56dbec20bff9f6f343800367287b48c0c28bf47f14b46aad3a32e4f24f0f5e"),
			Hex.decode("53e5a3fd40382755f582f4ff3a4ccb373babd087"),
			Hex.decode("ad8ce252fcac490700b7cecc560391ca783794a5bc86ab5892679bbcbabb5b73"),
			Hex.decode("a92e3fa63e8cbe50869fb352d883911271bf2b0e9048ad04c013b20e901f5806"),
			Hex.decode("45d6c9656b3b115263ba12739e90dcc1"),
			Hex.decode("f468986cbaeecabd4cf242607ac602b51a1adaf4f9a4fc5b298970cbda0b55c6")
	};
	
	private static final KeyPairType[] trueKeyPairTypes = {KeyPairType.ECP256, 
			KeyPairType.ECP384, KeyPairType.ECP512};
	@SuppressWarnings("deprecation")
	private static final KeyPairType falseKeyPairType = KeyPairType.DSA;
	private static final byte[][] truePublicKeys = {
			Hex.decode("3059301306072a8648ce3d020106082a8648ce3d030107034200040126491fbe391419fcdca058122a8520a816d3b7af9bc3a3af038e455b311b8234e5915ae2da11550a9f0ff9da5c65257c95c2bd3d5c21bcf16f6c15a94a50cb"),
			Hex.decode("3076301006072a8648ce3d020106052b81040022036200043a095518fc49cfaf6feb5af01cf71c02ebfff4fe581d93c6e252c8c607e6568db7267e0b958c4a262a6e6fa7c18572c3af59cd16535a28759d04488bae6c3014bbb4b89c25cbe3b76d7b540dabb13aed5793eb3ce572811b560bb18b00a5ac93"),
			Hex.decode("30819b301006072a8648ce3d020106052b8104002303818600040076083359c8b0b34a903461e435188cb90f7501bcb7ed97e8c506c5b60ff21178a625f80f5729ed4746d8e83b28145a51b9495880bf41b8ff0746ea0fe684832cc100ef1b01793c84abf64f31452d95bf0ef43d32440d8bc0d67501fcffaf51ae4956e5ff22f3baffea5edddbebbeed0ec3b4af28d18568aaf97b5cd026f6753881e0c4")
	};
	private static PublicKey[] publicKeys = new PublicKey[truePublicKeys.length];
	private static final byte[][] truePrivateKeys = {
			Hex.decode("3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420f8cb4b29aa51153ba811461e93fd1b2e69a127972f7100c5e246a3b2dcdd1b1c"),
			Hex.decode("304e020100301006072a8648ce3d020106052b81040022043730350201010430b88fe05d03b20dca95f19cb0fbabdfef1211452b29527ccac2ea37236d31ab6e7cada08315c62912b5c17cdf2d87fa3d"),
			Hex.decode("3060020100301006072a8648ce3d020106052b8104002304493047020101044201b4f573157d51f2e64a8b465fa92e52bae3529270951d448c18e4967beaa04b1f1fedb0e7a1e26f2eefb30566a479e1194358670b044fae438d11717eb2a795c3a8")
	};
	private static PrivateKey[] privateKeys = new PrivateKey[truePublicKeys.length];
	
	private static final byte[] trueIV = new byte[16];
	
	static{
		KeyPairType type;
		KeyFactory kf;
		X509EncodedKeySpec xks;
		PKCS8EncodedKeySpec pks;
		for(int i = 0; i < trueKeyPairTypes.length; i++){ 
			try {
				type = trueKeyPairTypes[i];
				kf = KeyFactory.getInstance(type.alg);
				xks = new X509EncodedKeySpec(truePublicKeys[i]);
				publicKeys[i] = kf.generatePublic(xks);
				pks = new PKCS8EncodedKeySpec(truePrivateKeys[i]);
				privateKeys[i] = kf.generatePrivate(pks);
			} catch (GeneralSecurityException e) {
				Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
			}
		}
	}
	
	
	public void testGenKeyPair() {
		for(KeyPairType type: trueKeyPairTypes){
			try {
				assertNotNull("KeyPairType: "+type.name(), KeyGenUtils.genKeyPair(type));
				} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}
	
	public void testGenKeyPairPublicKeyLenght() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			try {
				KeyPairType type = trueKeyPairTypes[i];
				byte[] publicKey = KeyGenUtils.genKeyPair(type).getPublic().getEncoded();
				assertEquals("KeyPairType: "+type.name(), truePublicKeys[i].length, publicKey.length);
			} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}
	
	public void testGenKeyPairDSAType() {
		boolean throwException = false;
		try{
			KeyGenUtils.genKeyPair(falseKeyPairType);
		} catch(UnsupportedTypeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGenKeyPairNullInput() {
		boolean throwException = false;
		try{
			KeyGenUtils.genKeyPair(null);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGetPublicKey() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			try{
				KeyPairType type = trueKeyPairTypes[i];
				PublicKey key = KeyGenUtils.getPublicKey(type, truePublicKeys[i]);
				assertTrue("KeyPairType: "+type.name(), MessageDigest.isEqual(key.getEncoded(), truePublicKeys[i]));
			} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}
	
	public void testGetPublicKeyDSAType() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKey(falseKeyPairType, null);
		} catch(UnsupportedTypeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetPublicKeyNullInput1() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKey(null, truePublicKeys[0]);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetPublicKeyNullInput2() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKey(trueKeyPairTypes[0], null);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetPublicKeyPair() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			try{
				KeyPairType type = trueKeyPairTypes[i];
				KeyPair key = KeyGenUtils.getPublicKeyPair(type, truePublicKeys[i]);
				assertTrue("KeyPairType: "+type.name(), MessageDigest.isEqual(key.getPublic().getEncoded(), truePublicKeys[i]));
				assertNull("KeyPairType: "+type.name(), key.getPrivate());
			} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}

	public void testGetPublicKeyPairNotNull() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			try {
				KeyPairType type = trueKeyPairTypes[i];
				assertNotNull("KeyPairType: "+type.name(), KeyGenUtils.getPublicKey(type, truePublicKeys[i]));
				} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}
	
	public void testGetPublicKeyPairDSAType() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKeyPair(falseKeyPairType, null);
		} catch(UnsupportedTypeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGetPublicKeyPairNullInput1() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKeyPair(null, truePublicKeys[0]);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetPublicKeyPairNullInput2() {
		boolean throwException = false;
		try{
			KeyGenUtils.getPublicKeyPair(trueKeyPairTypes[0], null);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}

	
	public void testGetKeyPairKeyPairTypeByteArrayByteArray() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			KeyPairType type = trueKeyPairTypes[i];
			try {
				assertNotNull("KeyPairType: "+type.name(), 
						KeyGenUtils.getKeyPair(type, truePublicKeys[i], truePrivateKeys[i]));
			} catch (UnsupportedTypeException e) {
				fail("UnsupportedTypeException thrown");
			}
		}
	}
	
	public void testGetKeyPairKeyPairTypeByteArrayDSAType() {
		boolean throwException = false;
		try{
			KeyGenUtils.getKeyPair(falseKeyPairType, null, null);
		} catch(UnsupportedTypeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGetKeyPairKeyPairTypeByteArrayNullInput1() {
		boolean throwException = false;
		try{
			KeyGenUtils.getKeyPair(null, truePublicKeys[0], truePrivateKeys[0]);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetKeyPairKeyPairTypeByteArrayNullInput2() {
		boolean throwException = false;
		try{
			KeyGenUtils.getKeyPair(trueKeyPairTypes[0], null, truePrivateKeys[0]);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetKeyPairKeyPairTypeByteArrayNullInput3() {
		boolean throwException = false;
		try{
			KeyGenUtils.getKeyPair(trueKeyPairTypes[0], truePublicKeys[0], null);
		} catch(NullPointerException e){
			throwException = true;
		} catch (UnsupportedTypeException e) {
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGetKeyPairPublicKeyPrivateKey() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			assertNotNull("KeyPairType: "+trueKeyPairTypes[i].name(), 
					KeyGenUtils.getKeyPair(publicKeys[i], privateKeys[i]));
		}
	}
	
	public void testGetKeyPairPublicKeyPrivateKeySamePublic() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			KeyPair pair = KeyGenUtils.getKeyPair(publicKeys[i], privateKeys[i]);
			assertEquals("KeyPairType: "+trueKeyPairTypes[i].name(), 
					pair.getPublic(), publicKeys[i]);
		}
	}
	
	public void testGetKeyPairPublicKeyPrivateKeySamePrivate() {
		for(int i = 0; i < trueKeyPairTypes.length; i++){
			KeyPair pair = KeyGenUtils.getKeyPair(publicKeys[i], privateKeys[i]);
			assertEquals("KeyPairType: "+trueKeyPairTypes[i].name(), 
					pair.getPrivate(), privateKeys[i]);
		}
	}

	public void testGenSecretKey() {
		for(KeyType type: keyTypes){
			assertNotNull("KeyType: "+type.name(), KeyGenUtils.genSecretKey(type));
		}
	}
	
	public void testGenSecretKeyKeySize() {
		for(KeyType type: keyTypes){
			byte[] key = KeyGenUtils.genSecretKey(type).getEncoded();
			assertEquals("KeyType: "+type.name(), type.keySize >> 3, key.length);
		}
	}
	
	public void testGenSecretKeyNullInput() {
		boolean throwException = false;
		try{
			KeyGenUtils.genSecretKey(null);
		} catch(NullPointerException e){
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGetSecretKey() {
		for(int i = 0; i < keyTypes.length; i++){
			KeyType type = keyTypes[i];
			SecretKey newKey = KeyGenUtils.getSecretKey(trueSecretKeys[i], type);
			assertTrue("KeyType: "+type.name(),
					MessageDigest.isEqual(trueSecretKeys[i], newKey.getEncoded()));
		}
	}
	
	public void testGetSecretKeyNullInput1() {
		boolean throwException = false;
		try{
			KeyGenUtils.getSecretKey(null, keyTypes[1]);
		} catch(IllegalArgumentException e){
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetSecretKeyNullInput2() {
		boolean throwException = false;
		try{
			KeyGenUtils.getSecretKey(trueSecretKeys[0], null);
		} catch(NullPointerException e){
			throwException = true;
		} 
		assertTrue(throwException);
	}

	public void testGenNonceLength() {
		assertEquals(KeyGenUtils.genNonce(trueLength).length, trueLength);
	}
	
	public void testGenNonceNegativeLength() {
		boolean throwException = false;
		try{
			KeyGenUtils.genNonce(falseLength);
		} catch(NegativeArraySizeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}

	public void testGenIV() {
		assertEquals(KeyGenUtils.genIV(trueLength).getIV().length, trueLength);
	}
	
	public void testGenIVNegativeLength() {
		boolean throwException = false;
		try{
			KeyGenUtils.genIV(falseLength);
		} catch(NegativeArraySizeException e){
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetIvParameterSpecLength() {
		assertEquals(KeyGenUtils.getIvParameterSpec(new byte[16], 0, trueLength).getIV().length, trueLength);
	}
	
	public void testGetIvParameterSpecNullInput() {
		boolean throwException = false;
		try{
			KeyGenUtils.getIvParameterSpec(null, 0, trueIV.length);
		} catch(IllegalArgumentException e){
			throwException = true;
		}
		assertTrue(throwException);
	}
	
	public void testGetIvParameterSpecOffsetOutOfBounds() {
		boolean throwException = false;
		try{
			KeyGenUtils.getIvParameterSpec(trueIV, -4, trueIV.length);
		} catch(ArrayIndexOutOfBoundsException e){
			throwException = true;
		} 
		assertTrue(throwException);
	}
	
	public void testGetIvParameterSpecLengthOutOfBounds() {
		boolean throwException = false;
		try{
			KeyGenUtils.getIvParameterSpec(trueIV, 0, trueIV.length+20);
		} catch(IllegalArgumentException e){
			throwException = true;
		} 
		assertTrue(throwException);
	}

}
