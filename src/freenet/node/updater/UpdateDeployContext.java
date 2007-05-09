package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Properties;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.l10n.L10n;

/**
 * Handles the wrapper.conf, essentially.
 */
class UpdateDeployContext {

	public class UpdateCatastropheException extends Exception {

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
		return L10n.getString("UpdateDeployContext."+key);
	}

	public static String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("UpdateDeployContext."+key, patterns, values);
	}

	public static String l10n(String key, String pattern, String value) {
		return L10n.getString("UpdateDeployContext."+key, pattern, value);
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
					writtenMain = true;
				} else if(writtenNewExt && line.startsWith("wrapper.java.classpath."+extClasspathNo+'=')) {
					bw.write("wrapper.java.classpath."+extClasspathNo+'='+newExt+'\n');
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

	}

}
