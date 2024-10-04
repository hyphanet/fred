/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.crypt;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.node.NodeStarter;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.StringCallback;
import java.net.ServerSocket;

public class SSL {

	private static final String KEY_ALGORITHM = "EC";
	private static final int KEY_SIZE = 256;
	private static final String SIG_ALGORITHM = "SHA256WithECDSA";

	private static final long CERTIFICATE_LIFETIME = 10 * 365 * 24 * 60 * 60; // 10 years
	private static final String CERTIFICATE_CN = "Freenet";
	private static final String CERTIFICATE_OU = "Freenet";
	private static final String CERTIFICATE_ON = "Freenet";

	private static final String CHAIN_ALIAS = "freenet";

	private static volatile boolean enable;
	private static KeyStore keystore;
	private static ServerSocketFactory ssf;
	private static String keyStore;
	private static String keyStorePass;
	private static String keyPass;

	/**
	 * Call this function before ask ServerSocket
	 * @return True is ssl is available
	 */
	public static boolean available() {
		return (ssf != null);
	}

	/**
	 * Configure SSL support
	 * @param sslConfig
	 */
	public static void init(SubConfig sslConfig) {
		int configItemOrder = 0;

		// Tracks config parameters related to a SSL
		sslConfig.register("sslEnable", false, configItemOrder++, true, true, "SSL.enable", "SSL.enable",
			new BooleanCallback() {

				@Override
				public Boolean get() {
					return enable;
				}

				@Override
				public void set(Boolean newValue) throws InvalidConfigValueException {
					if (!get().equals(newValue)) {
						enable = newValue;
						if(enable)
							try {
								loadKeyStoreAndCreateCertificate();
								createSSLContext();
							} catch(Exception e) {
								enable = false;
								e.printStackTrace(System.out);
								throwConfigError("SSL could not be enabled", e);
							}
						else {
							ssf = null;
							keyStore = null;
						}
					}
				}
			});

		sslConfig.register("sslKeyStore", "datastore/certs", configItemOrder++, true, true, "SSL.keyStore", "SSL.keyStore",
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
							throwConfigError("Keystore file could not be changed", e);
						}
					}
				}
			});

		sslConfig.register("sslKeyStorePass", "freenet", configItemOrder++, true, true, "SSL.keyStorePass", "SSL.keyStorePass",
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
							throwConfigError("Keystore password could not be changed", e);
						}
					}
				}
			});

		sslConfig.register("sslKeyPass", "freenet", configItemOrder++, true, true, "SSL.keyPass", "SSL.keyPass",
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
							Certificate[] chain = keystore.getCertificateChain(CHAIN_ALIAS);
							Key privKey = keystore.getKey(CHAIN_ALIAS, oldKeyPass.toCharArray());
							keystore.setKeyEntry(CHAIN_ALIAS, privKey, keyPass.toCharArray(), chain);
							createSSLContext();
						} catch(Exception e) {
							keyPass = oldKeyPass;
							e.printStackTrace(System.out);
							throwConfigError("Private key password could not be changed", e);
						}
					}
				}
			});

		enable = sslConfig.getBoolean("sslEnable");
		keyStore = sslConfig.getString("sslKeyStore");
		keyStorePass = sslConfig.getString("sslKeyStorePass");
		keyPass = sslConfig.getString("sslKeyPass");

		try {
			keystore = KeyStore.getInstance("PKCS12");
			loadKeyStore();
			createSSLContext();
		} catch(Exception e) {
			Logger.error(SSL.class, "Keystore cannot be loaded, SSL will be disabled", e);
		} finally {
			if(enable && !available()) {
				Logger.error(SSL.class, "SSL cannot be enabled!");
			} else if(enable) {
				Logger.normal(SSL.class, "SSL is enabled.");
			}
			sslConfig.finishedInitialization();
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
	 * Load key store from file but do not try to create a self-signed certificate when the file does not exist.
	 * Used when the node is starting up, but not enough entropy is collected to generate a certificate.
	 * @throws NoSuchAlgorithmException, CertificateException, IOException
	 */
	private static void loadKeyStore() throws NoSuchAlgorithmException, CertificateException, IOException {
		if(enable) {
			// A keystore is where keys and certificates are kept
			// Both the keystore and individual private keys should be password protected
			try (FileInputStream fis = new FileInputStream(keyStore)) {
				keystore.load(fis, keyStorePass.toCharArray());
			} catch(FileNotFoundException fnfe) {
				// If keystore not exist, create keystore and server certificate
				keystore.load(null, keyStorePass.toCharArray());
			}
		}
	}

	/**
	 * Load key store from file and create a self-signed certificate when the file does not exist.
	 */
	private static void loadKeyStoreAndCreateCertificate() throws NoSuchAlgorithmException, CertificateException, IOException, IllegalArgumentException, KeyStoreException, UnrecoverableKeyException, KeyManagementException, InvalidKeyException, NoSuchProviderException, SignatureException {
		if(enable) {
			// A keystore is where keys and certificates are kept
			// Both the keystore and individual private keys should be password protected
			try (FileInputStream fis = new FileInputStream(keyStore)) {
				keystore.load(fis, keyStorePass.toCharArray());
			} catch(FileNotFoundException fnfe) {
				createSelfSignedCertificate();
			}
		}
	}
	
	private static void createSelfSignedCertificate() throws NoSuchAlgorithmException, CertificateException, IOException, IllegalArgumentException, KeyStoreException, UnrecoverableKeyException, KeyManagementException, InvalidKeyException, NoSuchProviderException, SignatureException {
		// If keystore not exist, create keystore and server certificate
		keystore.load(null, keyStorePass.toCharArray());
		// Based on https://stackoverflow.com/questions/29852290/self-signed-x509-certificate-with-bouncy-castle-in-java

		// generate a key pair
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, "BC");
		keyPairGenerator.initialize(KEY_SIZE, NodeStarter.getGlobalSecureRandom());
		KeyPair keyPair = keyPairGenerator.generateKeyPair();

		// build a certificate generator
		X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
		X500Principal dnName = new X500Principal("CN=" + CERTIFICATE_CN + ", OU=" + CERTIFICATE_OU);

		// add some options
		certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
		certGen.setSubjectDN(new X509Name("dc=" + CERTIFICATE_CN));
		certGen.setIssuerDN(dnName); // use the same
		// now
		certGen.setNotBefore(new Date(System.currentTimeMillis()));
		// CERTIFICATE_LIFETIME
		certGen.setNotAfter(new Date(System.currentTimeMillis() + CERTIFICATE_LIFETIME * 1000));
		certGen.setPublicKey(keyPair.getPublic());
		certGen.setSignatureAlgorithm(SIG_ALGORITHM);
		certGen.addExtension(X509Extensions.ExtendedKeyUsage, true,
				new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));

		// finally, sign the certificate with the private key of the same KeyPair
		X509Certificate cert = certGen.generate(keyPair.getPrivate(), "BC");
		PrivateKey privKey = keyPair.getPrivate();
		Certificate[] chain = new Certificate[1];
		chain[0] = cert;
		keystore.setKeyEntry("freenet", privKey, keyPass.toCharArray(), chain);
		storeKeyStore();
		createSSLContext();
	}
	
	private static void storeKeyStore() throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		if(enable) {
			try (FileOutputStream fos = new FileOutputStream(keyStore);) {
				keystore.store(fos, keyStorePass.toCharArray());
			}
		}
	}

	private static void createSSLContext() throws NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException, KeyManagementException {
		if(enable) {
			// A KeyManagerFactory is used to create key managers
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			// Initialize the KeyManagerFactory to work with our keystore
			kmf.init(keystore, keyPass.toCharArray());
			// An SSLContext is an environment for implementing JSSE
			// It is used to create a ServerSocketFactory
			SSLContext sslc = SSLContext.getInstance("TLSv1.2");
			// Initialize the SSLContext to work with our key managers
			// FIXME: should we pass yarrow in here?
			sslc.init(kmf.getKeyManagers(), null, null);
			ssf = sslc.getServerSocketFactory();
		}
	}

	private static void throwConfigError(String message, Throwable cause)
			throws InvalidConfigValueException {
		String causeMsg = cause.getMessage();
		if (causeMsg == null) {
			causeMsg = cause.toString();
		}
		throw new InvalidConfigValueException(String.format("%s: %s", message, causeMsg));
	}
}
