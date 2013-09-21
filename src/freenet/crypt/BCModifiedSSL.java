/**
 * SSL.java modified to use Bouncy Castle's implementation. Possibly, it can replace the outdated SSL.java
 * Supports all functions of SSL.java including 
 * BCSSL.init() which stores/loads public key from node.crypt 
 * BCSSL.available() which says whether it is initialized
 * In addition, BCSSL.getSelfSignedCertificatePin() returns the SHA256 hash of SPKI of the certificate. (also called pin)
 */
package freenet.crypt;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.support.Logger;
import freenet.support.api.StringCallback;
import freenet.support.io.Closer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Formatter;
import java.util.logging.Level;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

public class BCModifiedSSL {
    private static KeyStore keystore;
    private static ServerSocketFactory ssf;
    private static String keyStore;
    private static String keyStorePass;
    private static String keyPass;
    private static String CERTIFICATE_CHAIN_ALIAS = "freenet";
    static {
        Security.addProvider(new BouncyCastleProvider());
    }
    
    /**
    * Call this function before ask ServerSocket
    * @return True is ssl is available
    */
    public static boolean available() {
		return (ssf != null);
    }
    /**
    * Copied from SSL.java
    * Configure SSL support
    * @param sslConfig
    */    
    public static void init(SubConfig sslConfig) {
        int configItemOrder = 0;
        sslConfig.register("sslKeyStore", "datastore/BCcerts", configItemOrder++, true, true, "SSL.keyStore", "SSL.keyStoreLong",
			new StringCallback() {

				@Override
				public String get() {
					return keyStore;
				}

				@Override
				public void set(String newKeyStore) throws InvalidConfigValueException {
					if(!newKeyStore.equals(get())) {
						String oldKeyStore = keyStore;
						keyStore = newKeyStore;
						try {
							loadKeyStore();
						} catch(Exception e) {
							keyStore = oldKeyStore;
							e.printStackTrace(System.out);
							throw new InvalidConfigValueException("Cannot change keystore file");
						}
					}
				}
			});
        sslConfig.register("sslKeyStorePass", "freenet", configItemOrder++, true, true, "SSL.keyStorePass", "SSL.keyStorePassLong",
			new StringCallback() {

				@Override
				public String get() {
					return keyStorePass;
				}

				@Override
				public void set(String newKeyStorePass) throws InvalidConfigValueException {
					if(!newKeyStorePass.equals(get())) {
						String oldKeyStorePass = keyStorePass;
						keyStorePass = newKeyStorePass;
						try {
							storeKeyStore();
						} catch(Exception e) {
							keyStorePass = oldKeyStorePass;
							e.printStackTrace(System.out);
							throw new InvalidConfigValueException("Cannot change keystore password");
						}
					}
				}
			});

		sslConfig.register("sslKeyPass", "freenet", configItemOrder++, true, true, "SSL.keyPass", "SSL.keyPassLong",
			new StringCallback() {

				@Override
				public String get() {
					return keyPass;
				}

				@Override
				public void set(String newKeyPass) throws InvalidConfigValueException {
					if(!newKeyPass.equals(get())) {
						String oldKeyPass = keyPass;
						keyPass = newKeyPass;
						try {
							Certificate[] chain = keystore.getCertificateChain("freenet");
							Key privKey = keystore.getKey("freenet", oldKeyPass.toCharArray());
							keystore.setKeyEntry("freenet", privKey, keyPass.toCharArray(), chain);
							createSSLContext();
						} catch(Exception e) {
							keyPass = oldKeyPass;
							e.printStackTrace(System.out);
							throw new InvalidConfigValueException("Cannot change private key password");
						}
					}
				}
			});
        keyStore = sslConfig.getString("sslKeyStore");
		keyStorePass = sslConfig.getString("sslKeyStorePass");
		keyPass = sslConfig.getString("sslKeyPass");
        try {
            keystore = KeyStore.getInstance("PKCS12");
            loadKeyStore();
            createSSLContext();
        } catch(Exception e) {
            Logger.error(SSL.class, "Cannot load keystore, ssl is disable", e);
	}
        
    }
    /** 
    * Create ServerSocket with ssl support
    * @return ServerSocket with ssl support
    * @throws IOException
    */
    public static ServerSocket createServerSocket() throws IOException {
        if(ssf == null)
            throw new IOException("SSL not initialized");
        return ssf.createServerSocket();
    }
	
    /**
    * Loads the keystore if it already exists. Otherwise, a self signed certificate is generated and stored
    */
    private static void loadKeyStore() throws NoSuchAlgorithmException, CertificateException, IOException, IllegalArgumentException, InstantiationException, IllegalAccessException, KeyStoreException, UnrecoverableKeyException, KeyManagementException, CertificateEncodingException, IllegalStateException, SignatureException, InvalidKeyException {
	// A keystore is where keys and certificates are kept
        // Both the keystore and individual private keys should be password protected
        FileInputStream fis = null;
	try {
            fis = new FileInputStream(keyStore);
            keystore.load(fis, keyStorePass.toCharArray());
        } catch(FileNotFoundException fnfe) {
            // If keystore not exist, create keystore and server certificate
            keystore.load(null, keyStorePass.toCharArray());
            try {
                Date start = new Date(System.currentTimeMillis());
                // One year in millis
                Date end = new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000); 
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
                keyPairGenerator.initialize(2048, new SecureRandom());
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                X509V3CertificateGenerator certGen;
                certGen = new X509V3CertificateGenerator();
                // "Freenet" is used as the SubjectDN, IssuerDN 
                X500Principal name = new X500Principal("CN=Freenet");
                certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
                certGen.setSubjectDN(name);
                certGen.setIssuerDN(name); // use the same
                certGen.setNotBefore(start);
                certGen.setNotAfter(end);
                certGen.setPublicKey(keyPair.getPublic());
                certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
                PrivateKey privKey = keyPair.getPrivate();    
                X509Certificate cert = certGen.generate(privKey, "BC");
                Certificate[] chain = new Certificate[1];
                chain[0] = cert;
                keystore.setKeyEntry(CERTIFICATE_CHAIN_ALIAS, privKey, keyPass.toCharArray(), chain);
                storeKeyStore();
                createSSLContext();
                } catch (NoSuchProviderException ex) {
                    throw new UnsupportedOperationException("BC not found!",ex);
                }
        } finally {
            Closer.close(fis);
        }
    }
	
    /**
    * Store the keystore into a file
    */
    private static void storeKeyStore() throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
    	FileOutputStream fos = null;
        try {
        	File file = new File(keyStore);
        	File directory = file.getParentFile();
        	if(!directory.exists() && !directory.mkdirs()){
        	    throw new IllegalStateException("Couldn't create directory " + directory);
        	}
        	if (!file.exists()) file.createNewFile();
        	fos = new FileOutputStream(file);
                keystore.store(fos, keyStorePass.toCharArray());
        }
        catch (Exception e) {
        	e.printStackTrace();
        }
        finally {
            Closer.close(fos);
        }
    }
	
    /**
    * Copied from SSL.java
    */
    private static void createSSLContext() throws NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException, KeyManagementException {
        // A KeyManagerFactory is used to create key managers
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        // Initialize the KeyManagerFactory to work with our keystore
        kmf.init(keystore, keyPass.toCharArray());
        // An SSLContext is an environment for implementing JSSE
        // It is used to create a ServerSocketFactory
        SSLContext sslc = SSLContext.getInstance("TLSv1");
        // Initialize the SSLContext to work with our key managers
        // FIXME: should we pass yarrow in here?
        sslc.init(kmf.getKeyManagers(), null, null);
        ssf = sslc.getServerSocketFactory();
    }
    
    /**
    * Get the SHA256 hash of our self-signed certificate
    */
    public static String getSelfSignedCertificatePin() throws IOException {
        if (ssf==null)throw new IOException("SSL not initialized");
        String pin = "";
        try {    
            Certificate certificate = keystore.getCertificateChain(CERTIFICATE_CHAIN_ALIAS)[0];
            MessageDigest digest= null;
                try {    
                    digest = MessageDigest.getInstance("SHA256");
                }
                catch(NoSuchAlgorithmException e) {
                    e.printStackTrace();
                }
            final byte[] spki          = certificate.getPublicKey().getEncoded();
            final byte[] pinBytes           = digest.digest(spki);
            Formatter formatter = new Formatter();
            for (byte b : pinBytes)
            {
                formatter.format("%02x", b);
            }
            pin= formatter.toString();
            formatter.close();
        } catch (KeyStoreException ex) {
            java.util.logging.Logger.getLogger(BCModifiedSSL.class.getName()).log(Level.SEVERE, null, ex);
        }
        return pin;
    }
}
