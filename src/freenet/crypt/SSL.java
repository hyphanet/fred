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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Arrays;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.StringCallback;
import freenet.support.io.Closer;
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
								loadKeyStore();
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
		}
		sslConfig.finishedInitialization();

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

	private static void loadKeyStore() throws NoSuchAlgorithmException, CertificateException, IOException, IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException, KeyStoreException, UnrecoverableKeyException, KeyManagementException {
		if(enable) {
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
					Class<?> certAndKeyGenClazz = anyClass(
						"sun.security.x509.CertAndKeyGen", // Java 7 and earlier
						"sun.security.tools.keytool.CertAndKeyGen" // Java 8 and later
					);
					Constructor<?> certAndKeyGenCtor = certAndKeyGenClazz.getConstructor(String.class, String.class, String.class);
					Object keypair = certAndKeyGenCtor.newInstance(KEY_ALGORITHM, SIG_ALGORITHM, "BC");

					Class<?> x500NameClazz = Class.forName("sun.security.x509.X500Name");
					Constructor<?> x500NameCtor = x500NameClazz.getConstructor(String.class, String.class,
					        String.class, String.class, String.class, String.class);
					Object x500Name = x500NameCtor.newInstance(CERTIFICATE_CN, CERTIFICATE_OU, CERTIFICATE_ON, "", "", "");
					
					Method certAndKeyGenGenerate = certAndKeyGenClazz.getMethod("generate", int.class);
					certAndKeyGenGenerate.invoke(keypair, KEY_SIZE);
					
					Method certAndKeyGetPrivateKey = certAndKeyGenClazz.getMethod("getPrivateKey");
					PrivateKey privKey = (PrivateKey) certAndKeyGetPrivateKey.invoke(keypair);

					Certificate[] chain = new Certificate[1];
					Method certAndKeyGenGetSelfCertificate = certAndKeyGenClazz.getMethod("getSelfCertificate",
					        x500NameClazz, long.class);
					chain[0] = (Certificate) certAndKeyGenGetSelfCertificate.invoke(keypair, x500Name,
						CERTIFICATE_LIFETIME);

					keystore.setKeyEntry("freenet", privKey, keyPass.toCharArray(), chain);
					storeKeyStore();
					createSSLContext();
				} catch (ClassNotFoundException cnfe) {
					throw new UnsupportedOperationException("The JVM you are using does not support generating strong SSL certificates", cnfe);
				} catch (NoSuchMethodException nsme) {
					throw new UnsupportedOperationException("The JVM you are using does not support generating strong SSL certificates", nsme);
				}
			} finally {
				Closer.close(fis);
			}
		}
	}

	private static void storeKeyStore() throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		if(enable) {
			FileOutputStream fos = null;
			try {
				fos = new FileOutputStream(keyStore);
				keystore.store(fos, keyStorePass.toCharArray());
			} finally {
				Closer.close(fos);
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

	private static Class<?> anyClass(String... names) throws ClassNotFoundException {
		for (String clazz : names) {
			try {
				return Class.forName(clazz);
			} catch (ClassNotFoundException e) {
				Logger.minor(SSL.class, "Class " + clazz + " not found", e);
			}
		}
		throw new ClassNotFoundException("Any of " + Arrays.toString(names));
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
