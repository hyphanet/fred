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

/**
 * Handles the wrapper.conf, essentially.
 */
class UpdateDeployContext {

	public class UpdateCatastropheException extends Exception {

		File oldConfig;
		File newConfig;
		
		UpdateCatastropheException(File oldConfig, File newConfig) {
			super("CATASTROPHIC ERROR: Deleted "+oldConfig+" but cannot rename "+newConfig+" to "+oldConfig+
					" THEREFORE THE NODE WILL NOT START! Please resolve the problem by renaming "+newConfig+" to "+oldConfig);
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
				if(!(name.startsWith("freenet") && (name.endsWith(".jar")))) {
					mainJar = f;
					newMainJar = new File(mainJar.getParent(), "freenet.jar");
					mainJarAbsolute = isAbsolute;
					mainClasspathNo = propNo;
					continue;
				} else if(!(name.startsWith("freenet") && (name.endsWith(".jar.new")))) {
					mainJar = f;
					newMainJar = new File(mainJar.getParent(), "freenet.jar.new");
					mainJarAbsolute = isAbsolute;
					mainClasspathNo = propNo;
					continue;
				}
			}
		}
		
		if(mainJar == null && extJar == null)
			throw new UpdaterParserException("Could not find freenet jars in wrapper.conf");
		if(mainJar == null)
			throw new UpdaterParserException("Could not find freenet.jar in wrapper.conf (did find freenet-ext.jar: "+extJar+')');
		if(extJar == null)
			throw new UpdaterParserException("Could not find freenet-ext.jar in wrapper.conf (did find freenet.jar: "+mainJar+')');
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

	void rewriteWrapperConf(boolean writtenNewJar, boolean writtenNewExt) throws IOException, UpdateCatastropheException {
		
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
		
		if(!(writtenMain && writtenExt)) {
			throw new IOException("Not able to update because of non-standard config: written main="+writtenMain+" ext="+writtenExt+" - should not happen! Report this to the devs, include your wrapper.conf");
		}

		if(!writtenReload) {
			// Add it.
			bw.write("wrapper.restart.reload_configuration=TRUE");
		}
		
		bw.close();

		if(!newConfig.renameTo(oldConfig)) {
			if(!oldConfig.delete()) {
				throw new IOException("Cannot delete "+oldConfig+" so cannot rename over it. Update failed.");
			}
			if(!newConfig.renameTo(oldConfig)) {
				throw new UpdateCatastropheException(oldConfig, newConfig);
			}
		}
		
		// New config installed.

	}

}
