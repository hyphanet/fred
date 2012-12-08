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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.client.FetchException;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.support.Executor;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.FileUtil.OperatingSystem;

/**
 * Parses the dependencies.properties file and ensures we have all the 
 * libraries required to use the next version. Calls the Deployer to do the
 * actual fetches, and to deploy the new version when we have everything 
 * ready.
 * 
 * We used to support a range of freenet-ext.jar versions. However, 
 * supporting ranges creates a lot of complexity, especially with Update 
 * Over Mandatory support.
 * 
 * File format of dependencies.properties:
 * [module].type=[module type]
 * CLASSPATH means the file must be downloaded, and then added to the 
 * classpath in wrapper.conf, before the update can be loaded.
 * 
 * OPTIONAL_PRELOAD means we just want to download the file.
 * 
 * [module].version=[version number]
 * Can often be parsed from MANIFEST.MF in Jar's, but that is NOT mandatory.
 * 
 * [module].filename=[preferred filename]
 * For CLASSPATH, this should be unique, i.e. include the version in the 
 * filename, e.g. freenet-ext-29.jar. For OPTIONAL_PRELOAD, we will often 
 * overwrite existing files.
 * 
 * [module].sha256=[hash in hex]
 * SHA256 hash of the file.
 * 
 * [module].filename-regex=[regular expression]
 * Matches filenames for this module. Only required for CLASSPATH. Note that
 * filenames will be toLowerCase()'ed first (but the regex isn't).
 * 
 * [module].key=[CHK URI]
 * Where to fetch the file from if we don't have it.
 * 
 * [module].size=[decimal size in bytes]
 * Size of the file.
 *
 * Optional:
 * 
 * [module].order=[decimal integer order, default is 0]
 * Ordering of CLASSPATH files within the wrapper.conf. E.g. freenet-ext.jar
 * is usually the last element because we want the earlier files to override
 * classes in it.
 * 
 * [module].os=[comma delimited list of OS's and pseudo-OS's]
 * OS's: See FileUtil.OperatingSystem: MacOS Linux FreeBSD GenericUnix Windows
 * Pseudo-Os's: ALL_WINDOWS ALL_UNIX ALL_MAC (these correspond to the booleans 
 * on FileUtil.OperatingSystem).
 * 
 * @author toad
 *
 */
public class MainJarDependenciesChecker {
	
	private static volatile boolean logMINOR;
	static {
		Logger.registerClass(MainJarDependenciesChecker.class);
	}
	
	// Lightweight interfaces, mundane glue code implemented by the caller.
	// FIXME unit testing should be straightforward, AND WOULD BE A GOOD IDEA!
	
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
		
		MainJarDependencies(TreeSet<Dependency> dependencies, int build) {
			this.dependencies = Collections.unmodifiableSortedSet(dependencies);
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
		public JarFetcher fetch(FreenetURI uri, File downloadTo, long expectedLength, byte[] expectedHash, JarFetcherCallback cb, int build, boolean essential) throws FetchException;
		/** Called by cleanup with the dependencies we can serve for the current version. 
		 * @param expectedHash The hash of the file's contents, which is also
		 * listed in the dependencies file.
		 * @param filename The local file to serve it from. */
		public void addDependency(byte[] expectedHash, File filename);
	}
	
	interface JarFetcher {
		public void cancel();
	}
	
	interface JarFetcherCallback {
		public void onSuccess();
		public void onFailure(FetchException e);
	}

	final class Dependency implements Comparable<Dependency> {
		private File oldFilename;
		private File newFilename;
		/** For last resort matching */
		private Pattern regex;
		private int order;
		
		private Dependency(File oldFilename, File newFilename, Pattern regex, int order) {
			this.oldFilename = oldFilename;
			this.newFilename = newFilename;
			this.regex = regex;
			this.order = order;
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

		@Override
		public int compareTo(Dependency arg0) {
			if(this == arg0) return 0;
			if(order > arg0.order) return 1;
			else if(order < arg0.order) return -1;
			// Filename comparisons aren't very reliable (e.g. "./test" versus "test" are not equals()!), go by getName() first.
			int ret = newFilename.getName().compareTo(arg0.newFilename.getName());
			if(ret != 0) return ret;
			return newFilename.compareTo(arg0.newFilename);
		}
		
	}
	
	MainJarDependenciesChecker(Deployer deployer, Executor executor) {
		this.deployer = deployer;
		this.executor = executor;
	}

	private final Deployer deployer;
	/** The final filenames we will use in the update, which we have 
	 * already downloaded. */
	private final TreeSet<Dependency> dependencies = new TreeSet<Dependency>();
	/** Set if the update can't be deployed because the dependencies file is 
	 * broken. We should wait for an update with a valid file. 
	 */
	private boolean broken = false;
	/** The build we are about to deploy */
	private int build;
	
	private class Downloader implements JarFetcherCallback {
		
		final JarFetcher fetcher;
		final Dependency dep;
		final boolean essential;
		final int forBuild;

		/** Construct with a Dependency, so we can add it when we're done. */
		Downloader(Dependency dep, FreenetURI uri, byte[] expectedHash, long expectedSize, boolean essential, int forBuild) throws FetchException {
			fetcher = deployer.fetch(uri, dep.newFilename, expectedSize, expectedHash, this, build, essential);
			this.dep = dep;
			this.essential = essential;
			this.forBuild = forBuild;
		}

		@Override
		public void onSuccess() {
			if(!essential) {
				System.out.println("Downloaded "+dep.newFilename+" - may be used by next update");
				return;
			}
			System.out.println("Downloaded "+dep.newFilename+" needed for update "+forBuild+"...");
			synchronized(MainJarDependenciesChecker.this) {
				downloaders.remove(this);
				if(forBuild != build) return;
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
				System.err.println("Failed to fetch "+dep.newFilename+" needed for next update ("+e.getShortMessage()+"). Will try again if we find a new freenet.jar.");
				synchronized(MainJarDependenciesChecker.this) {
					downloaders.remove(this);
					if(forBuild != build) return;
					broken = true;
				}
			}
		}
		
		public void cancel() {
			fetcher.cancel();
		}
		
	}
	
	private final HashSet<Downloader> downloaders = new HashSet<Downloader>();
	private final Executor executor;
	
	/** Parse the Properties file. Check whether we have the jars it refers to.
	 * If not, start fetching them.
	 * @param props The Properties parsed from the dependencies.properties file.
	 * @return The set of filenames needed if we can deploy immediately, in 
	 * which case the caller MUST deploy. */
	public synchronized MainJarDependencies handle(Properties props, int build) {
		try {
			return innerHandle(props, build);
		} catch (RuntimeException e) {
			broken = true;
			Logger.error(this, "MainJarDependencies parsing update dependencies.properties file broke: "+e, e);
			throw e;
		} catch (Error e) {
			broken = true;
			Logger.error(this, "MainJarDependencies parsing update dependencies.properties file broke: "+e, e);
			throw e;
		}
	}
	
	enum DEPENDENCY_TYPE {
		/** A jar we want to put on the classpath */
		CLASSPATH,
		/** A file to download, which does not block the update. */
		OPTIONAL_PRELOAD;
	}
	
	private synchronized MainJarDependencies innerHandle(Properties props, int build) {
		// FIXME support deletion placeholders.
		// I.e. when we remove a library we put a placeholder in to tell this code to delete it.
		// It's not acceptable to just delete stuff we don't know about.
		clear(build);
		HashSet<String> processed = new HashSet<String>();
		File[] list = new File(".").listFiles(new FileFilter() {

			@Override
			public boolean accept(File arg0) {
				if(!arg0.isFile()) return false;
				// Ignore non-jars regardless of what the regex says.
				String name = arg0.getName().toLowerCase();
				if(!(name.endsWith(".jar") || name.endsWith(".jar.new"))) return false;
				// FIXME similar checks elsewhere, factor out?
				if(name.equals("freenet.jar") || name.equals("freenet.jar.new") || name.equals("freenet-stable-latest.jar") || name.equals("freenet-stable-latest.jar.new"))
					return false;
				return true;
			}
			
		});
outer:	for(String propName : props.stringPropertyNames()) {
			if(!propName.contains(".")) continue;
			String baseName = propName.split("\\.")[0];
			if(!processed.add(baseName)) continue;
			String s = props.getProperty(baseName+".type");
			if(s == null) {
				Logger.error(this, "dependencies.properties broken? missing type for \""+baseName+"\"");
				broken = true;
				continue;
			}
			DEPENDENCY_TYPE type;
			try {
				type = DEPENDENCY_TYPE.valueOf(s);
			} catch (IllegalArgumentException e) {
				if(s.startsWith("OPTIONAL_")) {
					// We don't understand it, but that's OK as it's optional.
					if(logMINOR) Logger.minor(this, "Ignoring non-essential dependency type \""+s+"\" for \""+baseName+"\"");
					continue;
				}
				// We don't understand it, and it's not optional, so we can't deploy the update.
				Logger.error(this, "dependencies.properties broken? unrecognised type for \""+baseName+"\"");
				broken = true;
				continue;
			}
			
			// Check operating system restrictions.
			s = props.getProperty(baseName+".os");
			if(s != null) {
				if(!matchesCurrentOS(s)) {
					Logger.normal(this, "Ignoring "+baseName+" as not relevant to this operating system");
					continue;
				}
			}
			
			// Version is used in cleanup().
			String version = props.getProperty(baseName+".version");
			if(version == null) {
				Logger.error(this, "dependencies.properties broken? missing version");
				broken = true;
				continue;
			}
			File filename = null;
			s = props.getProperty(baseName+".filename");
			if(s != null) filename = new File(s);
			if(filename == null) {
				Logger.error(this, "dependencies.properties broken? missing filename");
				broken = true;
				continue;
			}
			FreenetURI maxCHK = null;
			s = props.getProperty(baseName+".key");
			if(s == null) {
				Logger.error(this, "dependencies.properties broken? missing "+baseName+".key");
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
			
			Pattern p = null;
			if(type == DEPENDENCY_TYPE.CLASSPATH) {
				// Regex used for matching filenames.
				String regex = props.getProperty(baseName+".filename-regex");
				if(regex == null && type == DEPENDENCY_TYPE.CLASSPATH) {
					// Not a critical error. Just means we can't clean it up, and can't identify whether we already have a compatible jar.
					Logger.error(this, "No "+baseName+".filename-regex in dependencies.properties - we will not be able to clean up old versions of files, and may have to download the latest version unnecessarily");
					// May be fatal later on depending on what else we have.
				}
				try {
					if(regex != null)
						p = Pattern.compile(regex);
				} catch (PatternSyntaxException e) {
					Logger.error(this, "Bogus Pattern \""+regex+"\" in dependencies.properties");
					p = null;
				}
			}
			
			byte[] expectedHash = parseExpectedHash(props.getProperty(baseName+".sha256"), baseName);
			if(expectedHash == null) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: No hash for "+baseName);
				broken = true;
				continue;
			}
			
			s = props.getProperty(baseName+".size");
			long size = -1;
			if(s != null) {
				try {
					size = Long.parseLong(s);
				} catch (NumberFormatException e) {
					size = -1;
				}
			}
			if(size < 0) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken length for "+baseName+" : \""+s+"\"");
				broken = true;
				continue;
			}
			
			int order = 0;
			File currentFile = null;

			if(type == DEPENDENCY_TYPE.CLASSPATH) {
				s = props.getProperty("order");
				if(s != null) {
					try {
						// Order is an optional field.
						// For most stuff we don't care.
						// But if it's present it must be correct!
						order = Integer.parseInt(s);
					} catch (NumberFormatException e) {
						System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken order for "+baseName+" : \""+s+"\"");
						broken = true;
						continue;
					}
				}
				
				currentFile = getDependencyInUse(baseName, p);
			}
			
			if(validFile(filename, expectedHash, size)) {
				// Nothing to do. Yay!
				System.out.println("Found file required by the new Freenet version: "+filename);
				// Use it.
				if(type == DEPENDENCY_TYPE.CLASSPATH)
					dependencies.add(new Dependency(currentFile, filename, p, order));
				continue;
			}
			// Check the version currently in use.
			if(currentFile != null && validFile(currentFile, expectedHash, size)) {
				System.out.println("Existing version of "+currentFile+" is OK for update.");
				// Use it.
				if(type == DEPENDENCY_TYPE.CLASSPATH)
					dependencies.add(new Dependency(currentFile, currentFile, p, order));
				continue;
			}
			if(type == DEPENDENCY_TYPE.CLASSPATH) {
				if(p == null) {
					// No way to check existing files.
					if(maxCHK != null) {
						try {
							fetchDependency(maxCHK, new Dependency(currentFile, filename, p, order), expectedHash, size, true);
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
				for(File f : list) {
					String name = f.getName();
					if(!p.matcher(name.toLowerCase()).matches()) continue;
					if(validFile(f, expectedHash, size)) {
						// Use it.
						System.out.println("Found "+name+" - meets requirement for "+baseName+" for next update.");
						dependencies.add(new Dependency(currentFile, f, p, order));
						continue outer;
					}
				}
			}
			if(maxCHK == null) {
				System.err.println("Cannot fetch "+baseName+" for update because no CHK and no old file");
				broken = true;
				continue;
			}
			// Otherwise we need to fetch it.
			try {
				fetchDependency(maxCHK, new Dependency(currentFile, filename, p, order), expectedHash, size, type != DEPENDENCY_TYPE.OPTIONAL_PRELOAD);
			} catch (FetchException e) {
				broken = true;
				Logger.error(this, "Failed to start fetch: "+e, e);
				System.err.println("Failed to start fetch of essential component for next release: "+e);
			}
		}
		if(ready())
			return new MainJarDependencies(new TreeSet<Dependency>(dependencies), build);
		else
			return null;
	}
	
	private static boolean matchesCurrentOS(String s) {
		OperatingSystem myOS = FileUtil.detectedOS;
		String[] osList = s.split(",");
		for(String os : osList) {
			os = os.trim();
			if(myOS.toString().equalsIgnoreCase(os)) {
				return true;
			}
			if(os.equalsIgnoreCase("ALL_WINDOWS") &&
					myOS.isWindows) {
				return true;
			}
			if(os.equalsIgnoreCase("ALL_UNIX") &&
					myOS.isUnix) {
				return true;
			}
			if(os.equalsIgnoreCase("ALL_MAC") &&
					myOS.isMac) {
				return true;
			}
		}
		return false;
	}

	/** Should be called on startup, before any fetches have started. Will 
	 * remove unnecessary files and start blob fetches for files we don't 
	 * have blobs for.
	 * @param props The dependencies.properties from the running version.
	 * @return True unless something went wrong.
	 */
	public static boolean cleanup(Properties props, final Deployer deployer, int build) {
		// This method should not change anything, but can call the callbacks.
		HashSet<String> processed = new HashSet<String>();
		final ArrayList<File> toDelete = new ArrayList<File>();
		File[] listMain = new File(".").listFiles(new FileFilter() {

			@Override
			public boolean accept(File arg0) {
				if(!arg0.isFile()) return false;
				String name = arg0.getName().toLowerCase();
				// Cleanup old updater tempfiles.
				if(name.endsWith(NodeUpdateManager.TEMP_FILE_SUFFIX) || name.endsWith(NodeUpdateManager.TEMP_BLOB_SUFFIX)) {
					toDelete.add(arg0);
					return false;
				}
				// Ignore non-jars regardless of what the regex says.
				if(!name.endsWith(".jar")) return false;
				// FIXME similar checks elsewhere, factor out?
				if(name.equals("freenet.jar") || name.equals("freenet.jar.new") || name.equals("freenet-stable-latest.jar") || name.equals("freenet-stable-latest.jar.new"))
					return false;
				return true;
			}
			
		});
		for(File f : toDelete) {
			System.out.println("Deleting old temp file \""+f+"\"");
			f.delete();
		}
		for(String propName : props.stringPropertyNames()) {
			if(!propName.contains(".")) continue;
			String baseName = propName.split("\\.")[0];
			if(!processed.add(baseName)) continue;
			String s = props.getProperty(baseName+".type");
			if(s == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing type for \""+baseName+"\"");
				continue;
			}
			DEPENDENCY_TYPE type;
			try {
				type = DEPENDENCY_TYPE.valueOf(s);
			} catch (IllegalArgumentException e) {
				if(s.startsWith("OPTIONAL_")) {
					if(logMINOR) Logger.minor(MainJarDependencies.class, "Ignoring non-essential dependency type \""+s+"\" for \""+baseName+"\"");
					continue;
				}
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? unrecognised type for \""+baseName+"\"");
				continue;
			}
			// Version is useful for checking for obsolete versions of files.
			String version = props.getProperty(baseName+".version");
			if(version == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing version");
				return false;
			}
			File filename = null;
			s = props.getProperty(baseName+".filename");
			if(s != null) filename = new File(s);
			if(filename == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing filename");
				return false;
			}
			
			// Check operating system restrictions.
			s = props.getProperty(baseName+".os");
			if(s != null) {
				if(!matchesCurrentOS(s)) {
					Logger.normal(MainJarDependenciesChecker.class, "Ignoring "+baseName+" as not relevant to this operating system");
					continue;
				}
			}
			
			final FreenetURI key;
			s = props.getProperty(baseName+".key");
			if(s == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing "+baseName+".key");
				return false;
			}
			try {
				key = new FreenetURI(s);
			} catch (MalformedURLException e) {
				Logger.error(MainJarDependencies.class, "Unable to parse CHK for "+baseName+": \""+s+"\": "+e, e);
				return false;
			}
			
			Pattern p = null;
			// Regex used for matching filenames.
			if(type == DEPENDENCY_TYPE.CLASSPATH) {
				String regex = props.getProperty(baseName+".filename-regex");
				if(regex == null) {
					Logger.error(MainJarDependencies.class, "No "+baseName+".filename-regex in dependencies.properties");
					return false;
				}
				try {
					p = Pattern.compile(regex);
				} catch (PatternSyntaxException e) {
					Logger.error(MainJarDependencies.class, "Bogus Pattern \""+regex+"\" in dependencies.properties");
					return false;
				}
			}
			
			final byte[] expectedHash = parseExpectedHash(props.getProperty(baseName+".sha256"), baseName);
			if(expectedHash == null) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: No hash for "+baseName);
				return false;
			}
			
			s = props.getProperty(baseName+".size");
			long size = -1;
			if(s != null) {
				try {
					size = Long.parseLong(s);
				} catch (NumberFormatException e) {
					size = -1;
				}
			}
			if(size < 0) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken length for "+baseName+" : \""+s+"\"");
				return false;
			}
			
			s = props.getProperty("order");
			if(s != null) {
				try {
					// Order is an optional field.
					// For most stuff we don't care.
					// But if it's present it must be correct!
					Integer.parseInt(s);
				} catch (NumberFormatException e) {
					System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken order for "+baseName+" : \""+s+"\"");
					continue;
				}
			}
			
			File currentFile = null;
			if(type == DEPENDENCY_TYPE.CLASSPATH)
				currentFile = getDependencyInUse(baseName, p);
			
			// Serve the file if it meets the hash in the dependencies.properties.
			if(currentFile != null && currentFile.exists()) {
				if(validFile(currentFile, expectedHash, size)) {
					System.out.println("Will serve "+currentFile+" for UOM");
					deployer.addDependency(expectedHash, currentFile);
				} else {
					System.out.println("Component "+baseName+" is using a non-standard file, we cannot serve the file "+filename+" via UOM to other nodes. Hence they may not be able to download the update from us.");
				}
			} else {
				// Not present even though it's required.
				// This means we want to preload it before the next release.
				final File file = filename;
				try {
					System.out.println("Preloading "+filename+" for the next update...");
					deployer.fetch(key, filename, size, expectedHash, new JarFetcherCallback() {

						@Override
						public void onSuccess() {
							System.out.println("Preloaded "+file+" which will be needed when we upgrade.");
							System.out.println("Will serve "+file+" for UOM");
							deployer.addDependency(expectedHash, file);
						}

						@Override
						public void onFailure(FetchException e) {
							Logger.error(this, "Failed to preload "+file+" from "+key+" : "+e, e);
						}
						
					}, build, false);
				} catch (FetchException e) {
					Logger.error(MainJarDependencies.class, "Failed to preload "+file+" from "+key+" : "+e, e);
				}
			}
			
			if(currentFile == null) 
				continue; // Ignore any old versions we might have missed that were actually on the classpath.
			String currentFileVersion = getDependencyVersion(currentFile);
			if(currentFileVersion == null)
				continue; // If no version in the current version, no version in any other either, can't reliably detect outdated jars. E.g. freenet-ext.jar up to v29!
			// Now delete bogus dependencies.
			for(File f : listMain) {
				String name = f.getName().toLowerCase();
				if(!p.matcher(name).matches()) continue;
				// Comparing File's by equals() is dodgy, e.g. ./blah != blah. So use getName().
				// Even on *nix some filesystems are case insensitive.
				if(name.equalsIgnoreCase(currentFile.getName())) continue;
				if(inClasspath(name)) continue; // Paranoia!
				String fileVersion = getDependencyVersion(f);
				if(fileVersion == null) {
					f.delete();
					System.out.println("Deleting old dependency file (no version): "+f);
					continue;
				}
				if(Fields.compareVersion(fileVersion, version) <= 0) {
					f.delete();
					System.out.println("Deleting old dependency file (outdated): "+f);
				} // Keep newer versions.
			}
		}
		return true;
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
        			final String key = "Implementation-Version";
        			BufferedInputStream bis = new BufferedInputStream(zis);
        			Manifest m = new Manifest(bis);
        			bis.close();
        			bis = null;
        			Attributes a = m.getMainAttributes();
        			if(a != null) {
        				String ver = a.getValue(key);
        				if(ver != null) return ver;
        			}
        			a = m.getAttributes("common");
        			if(a != null) {
        				String ver = a.getValue(key);
        				if(ver != null) return ver;
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

    /** Find the current filename, on the classpath, of the dependency given.
     * Note that this may not actually exist, and the caller should check!
     * However, even a non-existent filename may be useful when updating 
     * wrapper.conf.
     */
	private static File getDependencyInUse(String baseName, Pattern p) {
		if(p == null) return null; // Optional in some cases.
		String classpath = System.getProperty("java.class.path");
		String[] split = classpath.split(File.pathSeparator);
		for(String s : split) {
			File f = new File(s);
			if(p.matcher(f.getName().toLowerCase()).matches())
				return f;
		}
		return null;
	}
	
    private static boolean inClasspath(String name) {
		String classpath = System.getProperty("java.class.path");
		String[] split = classpath.split(File.pathSeparator);
		for(String s : split) {
			File f = new File(s);
			if(name.equalsIgnoreCase(f.getName()))
				return true;
		}
		return false;
	}

	private static byte[] parseExpectedHash(String sha256, String baseName) {
		if(sha256 == null) {
			Logger.error(MainJarDependencies.class, "No SHA256 for "+baseName+" in dependencies.properties");
			return null;
		}
		try {
			return HexUtil.hexToBytes(sha256);
		} catch (NumberFormatException e) {
			Logger.error(MainJarDependencies.class, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return null;
		} catch (IndexOutOfBoundsException e) {
			Logger.error(MainJarDependencies.class, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return null;
		}
	}

	public static boolean validFile(File filename, byte[] expectedHash, long size) {
		if(filename == null) return false;
		if(!filename.exists()) return false;
		if(filename.length() != size) {
			System.out.println("File exists while updating but length is wrong ("+filename.length()+" should be "+size+") for "+filename);
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
			return Arrays.equals(hash, expectedHash);
		} catch (FileNotFoundException e) {
			Logger.error(MainJarDependencies.class, "File not found: "+filename);
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
		final Downloader[] toCancel = downloaders.toArray(new Downloader[downloaders.size()]);
		executor.execute(new Runnable() {

			@Override
			public void run() {
				for(Downloader d : toCancel)
					d.cancel();
			}
			
		});
		downloaders.clear();
	}

	/** Unlike other methods here, this should be called outside the lock. */
	public void deploy() {
		TreeSet<Dependency> f;
		synchronized(this) {
			f = new TreeSet<Dependency>(dependencies);
		}
		if(logMINOR) Logger.minor(this, "Deploying build "+build+" with "+f.size()+" dependencies");
		deployer.deploy(new MainJarDependencies(f, build));
	}

	private synchronized void fetchDependency(FreenetURI chk, Dependency dep, byte[] expectedHash, long expectedSize, boolean essential) throws FetchException {
		Downloader d = new Downloader(dep, chk, expectedHash, expectedSize, essential, build);
		if(essential)
			downloaders.add(d);
	}

	private synchronized boolean ready() {
		if(broken) return false;
		if(!downloaders.isEmpty()) return false;
		return true;
	}
	
	public synchronized boolean isBroken() {
		return broken;
	}

}
