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

import freenet.l10n.NodeL10n;

/**
 * Handles the wrapper.conf, essentially.
 */
class UpdateDeployContext {

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
			
		boolean writtenReload = false;
		boolean writtenMain = false;
		boolean writtenExt = false;
		
		String newMain = mainJarAbsolute ? newMainJar.getAbsolutePath() : newMainJar.getPath();
		String newExt = extJarAbsolute ? newExtJar.getAbsolutePath() : newExtJar.getPath();
		
		String extLine = null;
		String mainLine = null;
		
		// We MUST put the ext before the main jar, or auto-update of freenet-ext.jar on Windows won't work.
		// The main jar refers to freenet-ext.jar so that java -jar freenet.jar works.
		// Therefore, on Windows, if we update freenet-ext.jar, it will use freenet.jar and freenet-ext.jar
		// and freenet-ext.jar.new as well. The old freenet-ext.jar will take precedence, and we won't be
		// able to overwrite either of them, so we'll just restart every 5 minutes forever!
		
		while((line = br.readLine()) != null) {
			
			if(line.startsWith("wrapper.java.classpath.")) {
				if(line.startsWith("wrapper.java.classpath."+mainClasspathNo+'=')) {
					if(writtenNewJar) {
						int higher = Math.max(mainClasspathNo, extClasspathNo);
						mainLine = "wrapper.java.classpath."+higher+'='+newMain;
					} else
						mainLine = line;
					if(extLine != null) {
						bw.write(extLine+'\n');
						bw.write(mainLine+'\n');
						writtenMain = true;
						writtenExt = true;
					}
				} else if(line.startsWith("wrapper.java.classpath."+extClasspathNo+'=')) {
					if(writtenNewExt) {
						int lower = Math.max(mainClasspathNo, extClasspathNo);
						extLine = "wrapper.java.classpath."+lower+'='+newExt;
					} else
						extLine = line;
					if(mainLine != null) {
						bw.write(extLine+'\n');
						bw.write(mainLine+'\n');
						writtenMain = true;
						writtenExt = true;
					}
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

}
