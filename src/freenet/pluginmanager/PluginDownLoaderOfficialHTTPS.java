/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.api.Bucket;
import freenet.support.io.ArrayBucket;
import freenet.support.io.FileBucket;
import freenet.support.io.FileUtil;

public class PluginDownLoaderOfficialHTTPS extends PluginDownLoaderURL {
	private static final String certurlNew = "freenet/clients/http/staticfiles/globalsign.pem";
	private static final String[] certURLs = new String[] { certurlNew };
	public static final String certfileOld = "startssl.pem";
	private static final String certfile = "sslcerts.pem";

	@Override
	public URL checkSource(String source) throws PluginNotFoundException {
		return super.checkSource("https://downloads.freenetproject.org/latest/" +
		source + ".jar");
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		return source + ".jar";
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		try {
			URL sha1url = new URL(getSource().toString()+".sha1");
			URLConnection urlConnection = sha1url.openConnection();
			urlConnection.setUseCaches(false);
			urlConnection.setAllowUserInteraction(false);
			
			InputStream is = openConnectionCheckRedirects(urlConnection);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
			byte[] buffer = new byte[1024];
			int read;
		
			while ((read = is.read(buffer)) != -1) {
				bos.write(buffer, 0, read);
			}
			
			return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1).split(" ")[0];
	
		} catch (MalformedURLException e) {
			throw new PluginNotFoundException("impossible: "+e,e);
		} catch (IOException e) {
			throw new PluginNotFoundException("Error while fetching sha1 for plugin: "+e,e);
		}
	}

	@Override
	InputStream getInputStream(PluginProgress progress) throws IOException {
		File TMP_KEYSTORE;
		try {
			TMP_KEYSTORE = File.createTempFile("keystore", ".tmp");
			TMP_KEYSTORE.deleteOnExit();
			
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, new char[0]);

			Collection<? extends Certificate> c;
			try (InputStream is = getCert()) {
				CertificateFactory cf = CertificateFactory.getInstance("X.509");
				c = cf.generateCertificates(is);
			}
			for (Certificate cert : c) {
				ks.setCertificateEntry(cert.getPublicKey().toString(), cert);
			}
			try (FileOutputStream tmpFOS = new FileOutputStream(TMP_KEYSTORE)) {
				ks.store(tmpFOS, new char[0]);
			}
			System.out.println("The CA has been imported into the trustStore");
		} catch(Exception e) {
			System.err.println("Error while handling the CA :" + e.getMessage());
			throw new IOException("Error while handling the CA : "+e);
		}

		System.setProperty("javax.net.ssl.trustStore", TMP_KEYSTORE.toString());
		
		return super.getInputStream(progress);
	}

	private InputStream getCert() throws IOException {
		
		// normal the file should be here,
		// left by installer or update script
		File certFile = new File(certfile).getAbsoluteFile();
		
		if (certFile.exists()) {
			return new FileInputStream(certFile);
		}
		
		Bucket bucket;
		try {
			bucket = new FileBucket(certFile, false, false, false, false);
			try (OutputStream os = bucket.getOutputStream()) {
				writeCerts(os);
			}
			return bucket.getInputStream();
		} catch (IOException e) {
			// We don't have access to TempBucketFactory here.
			// But the certs should be small, so just keep them in memory.
			bucket = new ArrayBucket();
			try (OutputStream os = bucket.getOutputStream()) {
				writeCerts(os);
			}
			return bucket.getInputStream();
		}
	}

	private static void writeCerts(OutputStream os) throws IOException {
		// try to create pem file
		ClassLoader loader = ClassLoader.getSystemClassLoader();
		for(String certurl : certURLs) {
			try (InputStream in = loader.getResourceAsStream(certurl)) {
				if (in != null) {
					FileUtil.copy(in, os, -1);
				} else {
					throw new IOException("Could not find certificates in fred source nor find certificates file");
				}
			}
		}
	}
	
	/** For the benefit mainly of the Windows updater script.
	 * It uses startssl.pem */
	public static void writeCertsTo(File file) throws IOException {
		FileOutputStream fos = new FileOutputStream(file);
		writeCerts(fos);
		fos.close();
	}

	public boolean isOfficialPluginLoader() {
		return true;
	}
	
}
