/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.support;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

/**
 * A simple class to load native libraries from the -ext jarfile
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 * 
 * TODO: make it more generic so that all libraries can use it (bigint, jcpuid, fec, ...)
 */
public class LibraryLoader {
	
	public static String getSimplifiedArchitecture() {
		String arch;
		if(System.getProperty("os.arch").toLowerCase().matches("(i?[x0-9]86_64|amd64)")) {
			arch = "amd64";
		} else if(System.getProperty("os.arch").toLowerCase().matches("(ppc)")) {
			arch = "ppc";
		} else {
			arch = "i386";
		}
		
		return arch;
	}
	
	public static void loadNative(String path, String libraryName) {
		final boolean isWindows = File.pathSeparatorChar == '\\';
		final String libraryNameWithPrefix = (isWindows ? "" : "lib") + libraryName;
		final String libraryNameWithPrefixAndArch = libraryNameWithPrefix + '-' + getSimplifiedArchitecture();
		final String libraryNameWithPrefixAndArchAndSuffix = libraryNameWithPrefixAndArch + (isWindows ? ".dll" : ".so");
		String resourceName = path + libraryNameWithPrefixAndArchAndSuffix;

		File nativeLib = new File((System.getProperty("java.library.path")) + "/lib" + libraryName + (isWindows ? ".dll" : ".so"));
		if (nativeLib.exists()) {
			System.out.println("Attempting to load the NativeThread library ["+libraryName+']');
			System.loadLibrary(libraryName);
		} else {
			try {
				// Get the resource
				URL resource = LibraryLoader.class.getResource(resourceName);

				// Get input stream from jar resource
				InputStream inputStream = resource.openStream();

				// Copy resource to filesystem in a temp folder with a unique name
				File temporaryLib = File.createTempFile(libraryNameWithPrefixAndArch, ".tmp");

				// Delete on exit the dll
				temporaryLib.deleteOnExit();

				FileOutputStream outputStream = new FileOutputStream(temporaryLib);
				byte[] array = new byte[2048];
				int read = 0;
				while((read = inputStream.read(array)) > 0) {
					outputStream.write(array, 0, read);
				}
				outputStream.close();

				// Finally, load the dll
				System.out.println("Attempting to load the "+libraryName+" library ["+resource+']');
				System.load(temporaryLib.getPath());
			} catch(Throwable e) {
				e.printStackTrace();
			}
		}
	}
}
