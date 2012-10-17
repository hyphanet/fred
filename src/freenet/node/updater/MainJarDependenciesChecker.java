/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.client.FetchException;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.Closer;

public class MainJarDependenciesChecker {
	
	// Slightly over-engineered? No.
	// This is critical code. It is essential that we are able to unit test it.
	// Hence the lightweight interfaces, with the mundane glue code implemented by the caller.
	
	class MainJarDependencies {
		/** The freenet.jar build to be deployed. It might be possible to
		 * deploy a new build without changing the wrapper. */
		final int build;
		/** The actual dependencies. */
		final Set<Dependency> dependencies;
		/** True if we must rewrite wrapper.conf, i.e. if any new jars have
		 * been added, or new versions of existing jars. Won't be reliably
		 * true in case of jars being removed at present. FIXME see comments
		 * in handle() about deletion placeholders! */
		final boolean mustRewriteWrapperConf;
		
		MainJarDependencies(Set<Dependency> dependencies, int build) {
			this.dependencies = dependencies;
			this.build = build;
			boolean mustRewrite = false;
			for(Dependency d : dependencies) {
				if(d.oldFilename == null || !d.oldFilename.equals(d.newFilename)) {
					mustRewrite = true;
					break;
				}
				if(File.pathSeparatorChar == ':' &&
						d.oldFilename != null && d.oldFilename.getName().equalsIgnoreCase("freenet-ext.jar.new")) {
					// If wrapper.conf currently contains freenet-ext.jar.new, we need to update wrapper.conf even
					// on unix. Reason: freenet-ext.jar.new won't be read if it's not the first item on the classpath,
					// because freenet.jar includes freenet-ext.jar implicitly via its manifest.
					mustRewrite = true;
					break;
				}
			}
			mustRewriteWrapperConf = mustRewrite;
		}
	}
	
	interface Deployer {
		public void deploy(MainJarDependencies deps);
		public JarFetcher fetch(FreenetURI uri, File downloadTo, JarFetcherCallback cb) throws FetchException;
	}
	
	interface JarFetcher {
		public void cancel();
	}
	
	interface JarFetcherCallback {
		public void onSuccess();
		public void onFailure(FetchException e);
	}

	final class Dependency {
		private File oldFilename;
		private File newFilename;
		/** For last resort matching */
		private Pattern regex;
		
		private Dependency(File oldFilename, File newFilename, Pattern regex) {
			this.oldFilename = oldFilename;
			this.newFilename = newFilename;
			this.regex = regex;
		}
		
		public File oldFilename() {
			return oldFilename;
		}
		
		public File newFilename() {
			return newFilename;
		}
		
		public Pattern regex() {
			return regex;
		}
	}
	
	MainJarDependenciesChecker(Deployer deployer) {
		this.deployer = deployer;
	}

	private final Deployer deployer;
	/** The final filenames we will use in the update, which we have 
	 * already downloaded. */
	private final HashSet<Dependency> dependencies = new HashSet<Dependency>();
	/** Set if the update can't be deployed because the dependencies file is 
	 * broken. We should wait for an update with a valid file. 
	 */
	private boolean broken = false;
	/** Set when we are ready to deploy. We won't look at new jars after that. */
	private boolean deploying = false;
	/** The build we are about to deploy */
	private int build;
	
	private class Downloader implements JarFetcherCallback {
		
		final JarFetcher fetcher;
		final Dependency dep;
		final boolean essential;

		/** Construct with a Dependency, so we can add it when we're done. */
		Downloader(Dependency dep, FreenetURI uri) throws FetchException {
			fetcher = deployer.fetch(uri, dep.newFilename, this);
			this.dep = dep;
			this.essential = true;
		}

		/** Download to a plain file. We won't use it this time. */
		Downloader(File filename, FreenetURI uri) throws FetchException {
			fetcher = deployer.fetch(uri, filename, this);
			this.dep = null;
			this.essential = false;
		}

		@Override
		public void onSuccess() {
			if(!essential) {
				System.out.println("Downloaded "+dep.newFilename+" - may be used by next update");
				return;
			}
			System.out.println("Downloaded "+dep.newFilename+" needed for update...");
			synchronized(MainJarDependenciesChecker.this) {
				downloaders.remove(this);
				dependencies.add(dep);
				if(!ready()) return;
			}
			deploy();
		}

		@Override
		public void onFailure(FetchException e) {
			if(!essential) {
				Logger.error(this, "Failed to pre-load "+dep.newFilename+" : "+e, e);
			} else {
				System.err.println("Failed to fetch "+dep.newFilename+" needed for next update. Will try again if we find a new freenet.jar.");
				synchronized(MainJarDependenciesChecker.this) {
					downloaders.remove(this);
					broken = false;
				}
			}
		}
		
		public void cancel() {
			fetcher.cancel();
		}
		
	}
	
	private final HashSet<Downloader> downloaders = new HashSet<Downloader>();
	
	/** Parse the Properties file. Check whether we have the jars it refers to.
	 * If not, start fetching them.
	 * @param props The Properties parsed from the dependencies.properties file.
	 * @return The set of filenames needed if we can deploy immediately, in 
	 * which case the caller MUST deploy. */
	public synchronized MainJarDependencies handle(Properties props, int build) {
		// FIXME support deletion placeholders.
		// I.e. when we remove a library we put a placeholder in to tell this code to delete it.
		// It's not acceptable to just delete stuff we don't know about.
		if(deploying) {
			Logger.error(this, "Already deploying?");
			return null;
		}
		clear(build);
		HashSet<String> processed = new HashSet<String>();
		File[] list = new File(".").listFiles(new FileFilter() {

			@Override
			public boolean accept(File arg0) {
				if(!arg0.isFile()) return false;
				// Ignore non-jars regardless of what the regex says.
				String name = arg0.getName().toLowerCase();
				if(!name.endsWith(".jar")) return false;
				// FIXME similar checks elsewhere, factor out?
				if(name.equals("freenet.jar") || name.equals("freenet.jar.new") || name.equals("freenet-stable-latest.jar") || name.equals("freenet-stable-latest.jar.new"))
					return false;
				return true;
			}
			
		});
		for(String propName : props.stringPropertyNames()) {
			if(!propName.contains(".")) continue;
			String baseName = propName.split(".")[0];
			if(baseName.equals("contrib"))
				// FIXME don't use parseManifest etc for freenet-ext.jar!
				continue;
			if(!processed.add(baseName)) continue;
			String minVersion = props.getProperty(baseName+".min.version");
			String maxVersion = props.getProperty(baseName+".max.version");
			if(minVersion == null || maxVersion == null) {
				Logger.error(this, "dependencies.properties broken? missing "+baseName+".min.version"+" or "+baseName+".max.version");
				broken = true;
				continue;
			}
			File minFilename = null, maxFilename = null;
			String s = props.getProperty(baseName+".min.filename");
			if(s != null) minFilename = new File(s);
			s = props.getProperty(baseName+".max.filename");
			if(s != null) maxFilename = new File(s);
			if(minFilename == null || maxFilename == null) {
				Logger.error(this, "dependencies.properties broken? missing "+baseName+".min.filename"+" or "+baseName+".max.filename");
				broken = true;
				continue;
			}
			FreenetURI maxCHK = null;
			s = props.getProperty(baseName+".max.key");
			if(s == null) {
				Logger.error(this, "dependencies.properties broken? missing "+baseName+".min.key");
				// Can't fetch it. :(
			} else {
				try {
					maxCHK = new FreenetURI(s);
				} catch (MalformedURLException e) {
					Logger.error(this, "Unable to parse CHK for "+baseName+": \""+s+"\": "+e, e);
					maxCHK = null;
				}
			}
			// FIXME where to get the proper folder from? That seems to be an issue in UpdateDeployContext as well...
			
			// Regex used for matching filenames.
			String regex = props.getProperty(baseName+".filename-regex");
			if(regex == null) {
				// Not a critical error. Just means we can't clean it up, and can't identify whether we already have a compatible jar.
				Logger.error(this, "No "+baseName+".filename-regex in dependencies.properties - we will not be able to clean up old versions of files, and may have to download the latest version unnecessarily");
				// May be fatal later on depending on what else we have.
			}
			Pattern p = null;
			try {
				if(regex != null)
					p = Pattern.compile(regex);
			} catch (PatternSyntaxException e) {
				Logger.error(this, "Bogus Pattern \""+regex+"\" in dependencies.properties");
				p = null;
			}
			
			// We need to determine whether it is in use at the moment.
			File currentFile = getDependencyInUse(baseName, p);
			
			if(validFile(baseName, maxFilename, props.getProperty(baseName+".max.sha256"))) {
				// Nothing to do. Yay!
				System.out.println("Found file required by the new Freenet version: "+maxFilename);
				// Use it.
				dependencies.add(new Dependency(currentFile, maxFilename, p));
				continue;
			}
			if(validFile(baseName, minFilename, props.getProperty(baseName+".min.sha256"))) {
				System.out.println("Found file required by the new Freenet version: "+minFilename+" (downloading a more recent version for the next update).");
				if(maxCHK != null) {
					// Fetch it in the background.
					try {
						fetchDependencyBackground(currentFile, maxCHK);
					} catch (FetchException e) {
						Logger.error(this, "Failed to start fetch (but not needed yet): "+e, e);
					}
				}
				// Use the old version.
				dependencies.add(new Dependency(minFilename, minFilename, p));
				continue;
			}
			
			// Check the version currently in use.
			String myVersion = null;
			if(currentFile != null)
				myVersion = getDependencyVersion(currentFile);
			if(myVersion != null) {
				// If the current version is okay, then use it.
				if(Fields.compareVersion(myVersion, minVersion) >= 0 &&
						Fields.compareVersion(myVersion, maxVersion) <= 0) {
					System.out.println("Existing version of "+baseName+" ("+myVersion+") is OK for update.");
					// Use it.
					dependencies.add(new Dependency(currentFile, currentFile, p));
					continue;
				}
			}
			// We might be somewhere in between.
			if(p == null) {
				// No way to check existing files.
				if(maxCHK != null) {
					try {
						fetchDependencyEssential(maxCHK, new Dependency(currentFile, maxFilename, p));
					} catch (FetchException fe) {
						broken = true;
						Logger.error(this, "Failed to start fetch: "+fe, fe);
						System.err.println("Failed to start fetch of essential component for next release: "+fe);
					}
				} else {
					// Critical error.
					System.err.println("Unable to fetch "+baseName+" because no URI and no regex to match old versions.");
					broken = true;
					continue;
				} 
				continue;
			}
			HashSet<File> toDelete = new HashSet<File>();
			String bestVersion = null;
			File bestFile = null;
			for(File f : list) {
				String name = f.getName();
				if(!p.matcher(name).matches()) continue;
				// Might be an old version.
				// We know it's not min or max.
				String tmpVersion = getDependencyVersion(f);
				boolean delete = false;
				if(tmpVersion == null)
					delete = true;
				else if(Fields.compareVersion(tmpVersion, minVersion) < 0)
					delete = true;
				else if(bestVersion == null || Fields.compareVersion(tmpVersion, bestVersion) > 0)
					delete = true;
				else if(Fields.compareVersion(tmpVersion, minVersion) > 0)
					continue; // Ignore newer.
				if(delete) {
					if(myVersion.equals(tmpVersion)) continue;
					if(bestFile != null)
						toDelete.add(bestFile);
					bestFile = f;
					bestVersion = tmpVersion;
				}
			}
			// We need to check whether it's in the range given above.
			if(bestVersion != null) {
				if(Fields.compareVersion(myVersion, minVersion) >= 0 &&
						Fields.compareVersion(myVersion, maxVersion) <= 0) {
					// Use it.
					System.out.println("Found "+bestFile+" - meets requirement for "+baseName+" for next update.");
					dependencies.add(new Dependency(currentFile, bestFile, p));
					continue;
				}
			}
			if(maxCHK == null) {
				System.err.println("Cannot fetch "+baseName+" for update because no CHK and no old file");
				broken = true;
				continue;
			}
			// Otherwise we need to fetch it.
			try {
				fetchDependencyEssential(maxCHK, new Dependency(currentFile, maxFilename, p));
			} catch (FetchException e) {
				broken = true;
				Logger.error(this, "Failed to start fetch: "+e, e);
				System.err.println("Failed to start fetch of essential component for next release: "+e);
			}
		}
		if(ready())
			return new MainJarDependencies(new HashSet<Dependency>(dependencies), build);
		else
			return null;
	}

	private File getDependencyInUse(String baseName, Pattern p) {
		String classpath = System.getProperty("java.class.path");
		String[] split = classpath.split(File.pathSeparator);
		for(String s : split) {
			File f = new File(s);
			if(p.matcher(f.getName()).matches())
				return f;
		}
		return null;
	}

	public static String getDependencyVersion(File currentFile) {
		// We can't use parseProperties because there are multiple sections.
		InputStream is = null;
		try {
			is = new FileInputStream(currentFile);
			ZipInputStream zis = new ZipInputStream(is);
			ZipEntry ze;
			while(true) {
				ze = zis.getNextEntry();
				if(ze == null) break;
				if(ze.isDirectory()) continue;
				String name = ze.getName();
				
				if(name.equals("META-INF/MANIFEST.MF")) {
					BufferedInputStream bis = new BufferedInputStream(zis);
					while(true) {
						Properties props = new Properties();
						props.load(bis);
						String version = props.getProperty("Implementation-Version");
						if(version != null) return version;
					}
				}
			}
			Logger.error(MainJarDependenciesChecker.class, "Unable to get dependency version from "+currentFile);
			return null;
		} catch (FileNotFoundException e) {
			return null;
		} catch (IOException e) {
			return null;
		} finally {
			Closer.close(is);
		}
	}

	private boolean validFile(String baseName, File filename, String sha256) {
		if(sha256 == null) {
			Logger.error(this, "No SHA256 for "+baseName+" in dependencies.properties");
			return false;
		}
		byte[] expectedHash;
		try {
			expectedHash = HexUtil.hexToBytes(sha256);
			// FIXME change these exceptions to something caught?
		} catch (NumberFormatException e) {
			Logger.error(this, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return false;
		} catch (IndexOutOfBoundsException e) {
			Logger.error(this, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return false;
		}
		
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(filename);
			MessageDigest md = SHA256.getMessageDigest();
			SHA256.hash(fis, md);
			byte[] hash = md.digest();
			SHA256.returnMessageDigest(md);
			fis.close();
			fis = null;
			if(Arrays.equals(hash, expectedHash))
				return true;
			else {
				System.out.println("File exists in update but bad hash: "+filename+" - deleting");
				filename.delete();
				return false;
			}
		} catch (FileNotFoundException e) {
			Logger.error(this, "File not found: "+filename);
			return false;
		} catch (IOException e) {
			System.err.println("Unable to read "+filename+" for updater");
			return false;
		} finally {
			Closer.close(fis);
		}
	}

	private synchronized void clear(int build) {
		dependencies.clear();
		broken = false;
		this.build = build;
		for(Downloader d : downloaders)
			d.cancel();
		downloaders.clear();
	}

	/** Unlike other methods here, this should be called outside the lock. */
	public void deploy() {
		HashSet<Dependency> f;
		synchronized(this) {
			f = new HashSet<Dependency>(dependencies);
		}
		deployer.deploy(new MainJarDependencies(f, build));
	}

	private synchronized void fetchDependencyEssential(FreenetURI chk, Dependency dep) throws FetchException {
		Downloader d = new Downloader(dep, chk);
		downloaders.add(d);
	}

	private synchronized void fetchDependencyBackground(File filename, FreenetURI chk) throws FetchException {
		Downloader d = new Downloader(filename, chk);
		downloaders.add(d);
	}

	private synchronized boolean ready() {
		if(broken) return false;
		if(!downloaders.isEmpty()) return false;
		deploying = true;
		return true;
	}

}
