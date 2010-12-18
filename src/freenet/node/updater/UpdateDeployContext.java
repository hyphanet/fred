package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Properties;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.l10n.NodeL10n;
import freenet.node.NodeInitException;
import freenet.node.updater.UpdateDeployContext.CHANGED;
import freenet.support.io.Closer;

/**
 * Handles the wrapper.conf, essentially.
 */
public class UpdateDeployContext {

	public static class UpdateCatastropheException extends Exception {

		private static final long serialVersionUID = 1L;
		File oldConfig;
		File newConfig;
		
		UpdateCatastropheException(File oldConfig, File newConfig) {
			super(l10n("updateCatastrophe", new String[] { "old", "new" },
					new String[] { oldConfig.toString(), newConfig.toString() }));
			this.oldConfig = oldConfig;
			this.newConfig = newConfig;
		}
		
	}

	File mainJar;
	File extJar;
	int mainClasspathNo;
	int extClasspathNo;
	File newMainJar;
	File newExtJar;
	boolean mainJarAbsolute;
	boolean extJarAbsolute;
	
	UpdateDeployContext() throws UpdaterParserException {
		Properties p = WrapperManager.getProperties();
		
		for(int propNo=1;true;propNo++) {
			String prop = p.getProperty("wrapper.java.classpath."+propNo);
			if(prop == null) break;
			File f = new File(prop);
			boolean isAbsolute = f.isAbsolute();
			String name = f.getName().toLowerCase();
			if(extJar == null) {
				if(name.equals("freenet-ext.jar.new")) {
					extJar = f;
					newExtJar = new File(extJar.getParent(), "freenet-ext.jar");
					extJarAbsolute = isAbsolute;
					extClasspathNo = propNo;
					continue;
				} else if(name.equals("freenet-ext.jar")) {
					extJar = f;
					newExtJar = new File(extJar.getParent(), "freenet-ext.jar.new");
					extClasspathNo = propNo;
					continue;
				}
			}
			if(mainJar == null) {
				// Try to match it
				if((name.startsWith("freenet") && (name.endsWith(".jar")))) {
					mainJar = f;
					newMainJar = new File(mainJar.getParent(), "freenet.jar.new");
					mainJarAbsolute = isAbsolute;
					mainClasspathNo = propNo;
					continue;
				} else if((name.startsWith("freenet") && (name.endsWith(".jar.new")))) {
					mainJar = f;
					newMainJar = new File(mainJar.getParent(), "freenet.jar");
					mainJarAbsolute = isAbsolute;
					mainClasspathNo = propNo;
					continue;
				}
			}
		}
		
		if(mainJar == null && extJar == null)
			throw new UpdaterParserException(l10n("cannotUpdateNoJars"));
		if(mainJar == null)
			throw new UpdaterParserException(l10n("cannotUpdateNoMainJar", "extFilename", extJar.toString()));
		if(extJar == null)
			throw new UpdaterParserException(l10n("cannotUpdateNoExtJar", "mainFilename", mainJar.toString()));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("UpdateDeployContext."+key);
	}

	public static String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("UpdateDeployContext."+key, patterns, values);
	}

	public static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("UpdateDeployContext."+key, pattern, value);
	}

	File getMainJar() {
		return mainJar;
	}

	File getNewMainJar() {
		return newMainJar;
	}

	File getExtJar() {
		return extJar;
	}
	
	File getNewExtJar() {
		return newExtJar;
	}

	void rewriteWrapperConf(boolean writtenNewJar, boolean writtenNewExt) throws IOException, UpdateCatastropheException, UpdaterParserException {
		
		// Rewrite wrapper.conf
		// Don't just write it out from properties; we want to keep it as close to what it was as possible.

		File oldConfig = new File("wrapper.conf");
		File newConfig = new File("wrapper.conf.new");
		
		if(!oldConfig.exists()) {
			File wrapperDir = new File("wrapper");
			if(wrapperDir.exists() && wrapperDir.isDirectory()) {
				File o = new File(wrapperDir, "wrapper.conf");
				if(o.exists()) {
					oldConfig = o;
					newConfig = new File(wrapperDir, "wrapper.conf.new");
				}
			}
		}
		
		FileInputStream fis = new FileInputStream(oldConfig);
		BufferedInputStream bis = new BufferedInputStream(fis);
		InputStreamReader isr = new InputStreamReader(bis);
		BufferedReader br = new BufferedReader(isr);
		
		FileOutputStream fos = new FileOutputStream(newConfig);
		OutputStreamWriter osw = new OutputStreamWriter(fos);
		BufferedWriter bw = new BufferedWriter(osw);

		String line;
			
		boolean writtenMain = false;
		boolean writtenExt = false;
		boolean writtenReload = false;
		
		String newMain = mainJarAbsolute ? newMainJar.getAbsolutePath() : newMainJar.getPath();
		String newExt = extJarAbsolute ? newExtJar.getAbsolutePath() : newExtJar.getPath();
		
		while((line = br.readLine()) != null) {
			
			if(line.startsWith("wrapper.java.classpath.")) {
				if(writtenNewJar && line.startsWith("wrapper.java.classpath."+mainClasspathNo+'=')) {
					bw.write("wrapper.java.classpath."+mainClasspathNo+'='+newMain+'\n');
					System.err.println("Rewritten wrapper.conf for main jar");
					writtenMain = true;
				} else if(writtenNewExt && line.startsWith("wrapper.java.classpath."+extClasspathNo+'=')) {
					bw.write("wrapper.java.classpath."+extClasspathNo+'='+newExt+'\n');
					System.err.println("Rewritten wrapper.conf for ext jar");
					writtenExt = true;
				} else {
					bw.write(line+'\n');
				}
			} else if(line.equalsIgnoreCase("wrapper.restart.reload_configuration=TRUE")) {
				writtenReload = true;
				bw.write(line+'\n');
			} else
				bw.write(line+'\n');
		}
		br.close();
		
		if(!((writtenMain || !writtenNewJar) && (writtenExt || !writtenNewExt))) {
			throw new UpdaterParserException(l10n("updateFailedNonStandardConfig", 
					new String[] { "main", "ext" }, new String[] { Boolean.toString(writtenMain), Boolean.toString(writtenExt) } ));
		}

		if(!writtenReload) {
			// Add it.
			bw.write("wrapper.restart.reload_configuration=TRUE");
		}
		
		bw.close();

		if(!newConfig.renameTo(oldConfig)) {
			if(!oldConfig.delete()) {
				throw new UpdaterParserException(l10n("updateFailedCannotDeleteOldConfig", "old", oldConfig.toString()));
			}
			if(!newConfig.renameTo(oldConfig)) {
				throw new UpdateCatastropheException(oldConfig, newConfig);
			}
		}
		
		// New config installed.
		
		System.err.println("Rewritten wrapper.conf for"+(writtenNewJar ? (" new main jar: "+newMainJar) : "")+(writtenNewExt ? (" new ext jar: "+newExtJar): ""));
		
	}

	public enum CHANGED {
		ALREADY, // Found the comment, so it has already been changed
		SUCCESS, // Succeeded
		FAIL // Failed e.g. due to unable to write wrapper.conf.
	}

	public static CHANGED tryIncreaseMemoryLimit(int extraMemoryMB,
			String markerComment) {
		// Rewrite wrapper.conf
		// Don't just write it out from properties; we want to keep it as close to what it was as possible.

		File oldConfig = new File("wrapper.conf");
		File newConfig = new File("wrapper.conf.new");
		
		if(!oldConfig.exists()) {
			File wrapperDir = new File("wrapper");
			if(wrapperDir.exists() && wrapperDir.isDirectory()) {
				File o = new File(wrapperDir, "wrapper.conf");
				if(o.exists()) {
					oldConfig = o;
					newConfig = new File(wrapperDir, "wrapper.conf.new");
				}
			}
		}
		
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		InputStreamReader isr = null;
		BufferedReader br = null;
		FileOutputStream fos = null;
		OutputStreamWriter osw = null;
		BufferedWriter bw = null;
		
		boolean success = false;
		
		try {
		
		fis = new FileInputStream(oldConfig);
		bis = new BufferedInputStream(fis);
		isr = new InputStreamReader(bis);
		br = new BufferedReader(isr);
		
		fos = new FileOutputStream(newConfig);
		osw = new OutputStreamWriter(fos);
		bw = new BufferedWriter(osw);

		String line;
		
		while((line = br.readLine()) != null) {
			
			if(line.equals("#" + markerComment))
				return CHANGED.ALREADY;
			
			if(line.startsWith("wrapper.java.maxmemory=")) {
				try {
					int memoryLimit = Integer.parseInt(line.substring("wrapper.java.maxmemory=".length()));
					int newMemoryLimit = memoryLimit + extraMemoryMB;
					// There have been some cases where really high limits have caused the JVM to do bad things.
					if(newMemoryLimit > 2048) newMemoryLimit = 2048;
					bw.write('#' + markerComment + '\n');
					bw.write("wrapper.java.maxmemory="+newMemoryLimit+'\n');
					success = true;
					continue;
				} catch (NumberFormatException e) {
					// Grrrrr!
				}
			}
			
			bw.write(line+'\n');
		}
		br.close();
		
		} catch (IOException e) {
			newConfig.delete();
			System.err.println("Unable to rewrite wrapper.conf with new memory limit.");
			return CHANGED.FAIL;
		} finally {
			Closer.close(br);
			Closer.close(isr);
			Closer.close(bis);
			Closer.close(fis);
			Closer.close(bw);
			Closer.close(osw);
			Closer.close(fos);
		}
		
		if(success) {
			if(!newConfig.renameTo(oldConfig)) {
				if(!oldConfig.delete()) {
					System.err.println("Unable to move rewritten wrapper.conf with new memory limit "+newConfig+" over old config "+oldConfig+" : unable to delete old config");
					return CHANGED.FAIL;
				}
				if(!newConfig.renameTo(oldConfig)) {
					System.err.println("Old wrapper.conf deleted but new wrapper.conf cannot be renamed!");
					System.err.println("FREENET WILL NOT START UNTIL YOU RENAME "+newConfig+" to "+oldConfig);
					System.exit(NodeInitException.EXIT_BROKE_WRAPPER_CONF);
				}
			}
			System.err.println("Rewritten wrapper.conf for new memory limit");
			return CHANGED.SUCCESS;
		} else {
			newConfig.delete();
			return CHANGED.FAIL;
		}
	}

}
