/**
 * Elliptical Curve DSA implementation from BouncyCastle
 * TODO: Store in node.crypt instead of using properties file
 */
package freenet.darknetapp;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;
/**
 * @author Illutionist
 */
public class ECDSA {
    private static PublicKey publickey;
    private static PrivateKey privatekey;
    private static boolean generated = false;
    private static String fileName = "ECDSAconfig.properties";
    private static Properties prop = new Properties();    
    static {
        Security.addProvider(new BouncyCastleProvider());
    }
    /**
     * If already intialized, return public key. Otherwise generate public, private keys and then return public key 
     * @return Public Key in bytes
     * @throws UnsupportedEncodingException 
     */
    public static byte[] getPublicKey() throws UnsupportedEncodingException  {
        byte[] key = null;
        if (!generated) {
            initialize();
        }
        if (generated) key = publickey.getEncoded();
        return key;
    }
    
    /**
     * If already initialized with public and private keys, sign
     * Otherwise, generate the properties and then sign
     * @param text Text to be signed
     * @return EC Signature in bytes 
     */
    public static byte[] getSignature(String text)  {
        byte[] signature = null;
        String sign = "";
        if (!generated) {
            initialize();
        }
        if (generated){
                try { 
                    Signature dsa = Signature.getInstance("SHA1withECDSA", "BC");
                    dsa.initSign(privatekey);
                    byte[] buf = text.getBytes("UTF-8");
                    dsa.update(buf, 0, buf.length);
                    signature = dsa.sign();
                    sign = new String(signature,"UTF-8");
                } catch (NoSuchAlgorithmException ex) {
                    Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
                } catch (NoSuchProviderException ex) {
                    Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
                } catch (InvalidKeyException ex) {
                    Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
                } catch (SignatureException ex) {
                    Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
                } catch (UnsupportedEncodingException ex) {
                Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return signature;
    }
    
    /**
     * Ideally, private, public keys are generated in the first run and stored in a file
     * Subsequently, these are pulled from the file and utilized
     */
    public static void initialize() {
        try {
            File file = new File(fileName);
            if (!file.exists()) {
                file.createNewFile();              
                prop.load(new FileInputStream(file));
                generateProperties();
            }
            else {
                prop.load(new FileInputStream(file));
                pullProperties();
            }
        }
        catch (UnsupportedEncodingException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchProviderException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeySpecException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Create the public, private keys using a random seed. These are stored in Base64 in properties file
     * @throws NoSuchAlgorithmException
     * @throws NoSuchProviderException
     * @throws UnsupportedEncodingException
     * @throws IOException 
     */
    private static void generateProperties() throws NoSuchAlgorithmException, NoSuchProviderException, UnsupportedEncodingException, IOException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
        keyGen.initialize(256, random);
        KeyPair pair = keyGen.generateKeyPair();
        privatekey = pair.getPrivate();
        publickey = pair.getPublic();
        BASE64Encoder encoder = new BASE64Encoder();
        String pri = encoder.encode(privatekey.getEncoded());
        String pub = encoder.encode(publickey.getEncoded());
        prop.setProperty("DSAprivatekey",pri);
        prop.setProperty("DSApublickey",pub);
        generated = true;
        prop.store(new FileOutputStream(fileName), null);
        
    }
    /**
     * Pull the Base64 encoded public and private keys from properties file
     * @throws UnsupportedEncodingException
     * @throws NoSuchAlgorithmException
     * @throws NoSuchProviderException
     * @throws InvalidKeySpecException
     * @throws IOException 
     */
    private static void pullProperties() throws UnsupportedEncodingException, NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException, IOException {
        String priv = prop.getProperty("DSAprivatekey");
        String publ = prop.getProperty("DSApublickey");
        BASE64Decoder decoder = new BASE64Decoder();
        byte[] pri= decoder.decodeBuffer(priv);
        byte[] pub = decoder.decodeBuffer(publ);
        PKCS8EncodedKeySpec priKeySpec = new PKCS8EncodedKeySpec(pri);
        X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(pub);
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        privatekey =keyFactory.generatePrivate(priKeySpec);
        publickey =keyFactory.generatePublic(pubKeySpec);
        generated = true;
    }
    
    /**
     * Verify the signature against the data when public key is specified
     * @param data
     * @param signature
     * @param publicKey
     * @return the verification result
     * @return false in case of any exception
     */
    public static boolean verify(String data,byte[] signature,byte[] publicKey) {
        boolean verify = false;
        try {
            
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(publicKey);
            KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
            PublicKey pubKey = keyFactory.generatePublic(pubKeySpec);
            byte[] buf = data.getBytes("UTF-8");
            Signature sig = Signature.getInstance("SHA1withECDSA", "BC");
            sig.initVerify(pubKey);
            sig.update(buf, 0,buf.length);
            verify = sig.verify(signature);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchProviderException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeySpecException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidKeyException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SignatureException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(ECDSA.class.getName()).log(Level.SEVERE, null, ex);
        }
        return verify;
    }
}
