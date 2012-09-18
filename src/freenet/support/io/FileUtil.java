/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.Random;

import freenet.client.DefaultMIMETypes;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.StringValidityChecker;
import freenet.support.Logger.LogLevel;

final public class FileUtil {

	private static final int BUFFER_SIZE = 32*1024;

	public static enum OperatingSystem {
		All,
		MacOS,
		Linux,
		FreeBSD,
		GenericUnix,
		Windows
	};

	public static final OperatingSystem detectedOS;

	private static final Charset fileNameCharset;

	static {
		detectedOS = detectOperatingSystem();

		// I did not find any way to detect the Charset of the file system so I'm using the file encoding charset.
		// On Windows and Linux this is set based on the users configured system language which is probably equal to the filename charset.
		// The worst thing which can happen if we misdetect the filename charset is that downloads fail because the filenames are invalid:
		// We disallow path and file separator characters anyway so its not possible to cause files to be stored in arbitrary places.
		fileNameCharset = getFileEncodingCharset();
	}

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * Detects the operating system in which the JVM is running. Returns OperatingSystem.All if the OS is unknown or an error occured.
	 * Therefore this function should never throw.
	 */
	private static OperatingSystem detectOperatingSystem() { // TODO: Move to the proper class
		try {
			final String name =  System.getProperty("os.name").toLowerCase();

			// Order the if() by probability instead alphabetically to decrease the false-positive rate in case they decide to call it "Windows Mac" or whatever

			// Please adapt sanitizeFileName when adding new OS.

			if(name.indexOf("win") >= 0)
				return OperatingSystem.Windows;

			if(name.indexOf("mac") >= 0)
				return OperatingSystem.MacOS;

			if(name.indexOf("linux") >= 0)
				return OperatingSystem.Linux;
			
			if(name.indexOf("freebsd") >= 0)
				return OperatingSystem.FreeBSD;
			
			if(name.indexOf("unix") >= 0)
				return OperatingSystem.GenericUnix;
			else if(File.separatorChar == '/')
				return OperatingSystem.GenericUnix;
			else if(File.separatorChar == '\\')
				return OperatingSystem.Windows;

			Logger.error(FileUtil.class, "Unknown operating system:" + name);
		} catch(Throwable t) {
			Logger.error(FileUtil.class, "Operating system detection failed", t);
		}

		return OperatingSystem.All;
	}

	/**
	 * Returns the Charset which is equal to the "file.encoding" property.
	 * This property is set to the users configured system language on windows for example.
	 *
	 * If any error occurs, the default Charset is returned. Therefore this function should never throw.
	 */
	public static Charset getFileEncodingCharset() {
		try {
			return Charset.forName(System.getProperty("file.encoding"));
		} catch(Throwable t) {
			return Charset.defaultCharset();
		}
	}


	/** Round up a value to the next multiple of a power of 2 */
	private static long roundup_2n (long val, int blocksize) {
		int mask=blocksize-1;
		return (val+mask)&~mask;
	}

	/**
	 * Guesstimate real disk usage for a file with a given filename, of a given length.
	 */
	public static long estimateUsage(File file, long flen) {
		/**
		 * It's possible that none of these assumptions are accurate for any filesystem;
		 * this is intended to be a plausible worst case.
		 */
		// Assume 4kB clusters for calculating block usage (NTFS)
		long blockUsage = roundup_2n(flen, 4096);
		// Assume 512 byte filename entries, with 100 bytes overhead, for filename overhead (NTFS)
		String filename = file.getName();
		int nameLength = filename.getBytes().length + 100;
		long filenameUsage = roundup_2n(nameLength, 512);
		// Assume 50 bytes per block tree overhead with 1kB blocks (reiser3 worst case)
		long extra = (roundup_2n(flen, 1024) / 1024) * 50;
		return blockUsage + filenameUsage + extra;
	}

	/**
	 *  Is possParent a parent of filename?
	 * Why doesn't java provide this? :(
	 * */
	public static boolean isParent(File poss, File filename) {
		File canon = FileUtil.getCanonicalFile(poss);
		File canonFile = FileUtil.getCanonicalFile(filename);

		if(isParentInner(poss, filename)) return true;
		if(isParentInner(poss, canonFile)) return true;
		if(isParentInner(canon, filename)) return true;
		if(isParentInner(canon, canonFile)) return true;
		return false;
	}

	private static boolean isParentInner(File possParent, File filename) {
		while(true) {
			if(filename.equals(possParent)) return true;
			filename = filename.getParentFile();
			if(filename == null) return false;
		}
	}

	public static File getCanonicalFile(File file) {
		// Having some problems storing File's in db4o ...
		// It would start up, and canonicalise a file with path "/var/lib/freenet-experimental/persistent-temp-24374"
		// to /var/lib/freenet-experimental/var/lib/freenet-experimental/persistent-temp-24374
		// (where /var/lib/freenet-experimental is the current working dir)
		// Regenerating from path worked. So do that here.
		// And yes, it's voodoo.
		String name = file.getPath();
		if(File.pathSeparatorChar == '\\') {
			name = name.toLowerCase();
		}
		file = new File(name);
		File result;
		try {
			result = file.getAbsoluteFile().getCanonicalFile();
		} catch (IOException e) {
			result = file.getAbsoluteFile();
		}
		return result;
	}

        public static String readUTF(File file) throws FileNotFoundException, IOException {
            return readUTF(file, 0);
        }

	public static String readUTF(File file, long offset) throws FileNotFoundException, IOException {
		StringBuilder result = new StringBuilder();
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		InputStreamReader isr = null;

		try {
			fis = new FileInputStream(file);
			skipFully(fis, offset);
			bis = new BufferedInputStream(fis);
			isr = new InputStreamReader(bis, "UTF-8");

			char[] buf = new char[4096];
			int length = 0;

			while((length = isr.read(buf)) > 0) {
				result.append(buf, 0, length);
			}

		} finally {
			Closer.close(isr);
			Closer.close(bis);
			Closer.close(fis);
		}
		return result.toString();
	}

	/**
	 * Reliably skip a number of bytes or throw.
	 */
	public static void skipFully(InputStream is, long skip) throws IOException {
		long skipped = 0;
		while(skipped < skip) {
			long x = is.skip(skip - skipped);
			if(x <= 0) throw new IOException("Unable to skip "+(skip - skipped)+" bytes");
			skipped += x;
		}
	}

	public static boolean writeTo(InputStream input, File target) throws FileNotFoundException, IOException {
		DataInputStream dis = null;
		FileOutputStream fos = null;
		File file = File.createTempFile("temp", ".tmp", target.getParentFile());
		if(logMINOR)
			Logger.minor(FileUtil.class, "Writing to "+file+" to be renamed to "+target);

		try {
			dis = new DataInputStream(input);
			fos = new FileOutputStream(file);

			int len = 0;
			byte[] buffer = new byte[4096];
			while ((len = dis.read(buffer)) > 0) {
				fos.write(buffer, 0, len);
			}
		} catch (IOException e) {
			throw e;
		} finally {
			if(dis != null) dis.close();
			if(fos != null) fos.close();
		}

		if(FileUtil.renameTo(file, target))
			return true;
		else {
			file.delete();
			return false;
		}
	}

        public static boolean renameTo(File orig, File dest) {
            // Try an atomic rename
            // Shall we prevent symlink-race-conditions here ?
            if(orig.equals(dest))
                throw new IllegalArgumentException("Huh? the two file descriptors are the same!");
            if(!orig.exists()) {
            	throw new IllegalArgumentException("Original doesn't exist!");
            }
            if (!orig.renameTo(dest)) {
                // Not supported on some systems (Windows)
                if (!dest.delete()) {
                    if (dest.exists()) {
                        Logger.error("FileUtil", "Could not delete " + dest + " - check permissions");
                        System.err.println("Could not delete " + dest + " - check permissions");
                    }
                }
                if (!orig.renameTo(dest)) {
                	String err = "Could not rename " + orig + " to " + dest +
                    	(dest.exists() ? " (target exists)" : "") +
                    	(orig.exists() ? " (source exists)" : "") +
                    	" - check permissions";
                    Logger.error(FileUtil.class, err);
                    System.err.println(err);
                    return false;
                }
            }
            return true;
        }

        /**
         * Like renameTo(), but can move across filesystems, by copying the data.
         * @param f
         * @param file
         */
    	public static boolean moveTo(File orig, File dest, boolean overwrite) {
            if(orig.equals(dest))
                throw new IllegalArgumentException("Huh? the two file descriptors are the same!");
            if(!orig.exists()) {
            	throw new IllegalArgumentException("Original doesn't exist!");
            }
            if(dest.exists()) {
            	if(overwrite)
            		dest.delete();
            	else {
            		System.err.println("Not overwriting "+dest+" - already exists moving "+orig);
            		return false;
            	}
            }
    		if(!orig.renameTo(dest)) {
    			// Copy the data
    			InputStream is = null;
    			OutputStream os = null;
    			try {
    				is = new FileInputStream(orig);
    				os = new FileOutputStream(dest);
    				copy(is, os, orig.length());
    				is.close();
    				is = null;
    				os.close();
    				os = null;
    				orig.delete();
    				return true;
    			} catch (IOException e) {
    				dest.delete();
    				Logger.error(FileUtil.class, "Move failed from "+orig+" to "+dest+" : "+e, e);
    				System.err.println("Move failed from "+orig+" to "+dest+" : "+e);
    				e.printStackTrace();
    				return false;
    			} finally {
    				Closer.close(is);
    				Closer.close(os);
    			}
    		} else return true;
    	}

    /**
     * Sanitizes the given filename to be valid on the given operating system.
     * If OperatingSystem.All is specified this function will generate a filename which fullfils the restrictions of all known OS, currently
     * this is MacOS, Unix and Windows.
     */
	public static String sanitizeFileName(final String fileName, OperatingSystem targetOS, String extraChars) {
		// Filter out any characters which do not exist in the charset.
		final CharBuffer buffer = fileNameCharset.decode(fileNameCharset.encode(fileName)); // Charset are thread-safe

		final StringBuilder sb = new StringBuilder(fileName.length() + 1);

		switch(targetOS) {
			case All: break;
			case MacOS: break;
			case Linux: break;
			case FreeBSD: break;
			case GenericUnix: break;
			case Windows: break;
			default:
				Logger.error(FileUtil.class, "Unsupported operating system: " + targetOS);
				targetOS = OperatingSystem.All;
				break;
		}
		
		char def = ' ';
		if(extraChars.indexOf(' ') != -1) {
			def = '_';
			if(extraChars.indexOf(def) != -1) {
				def = '-';
				if(extraChars.indexOf(def) != -1)
					throw new IllegalArgumentException("What do you want me to use instead of spaces???");
			}
		}

		for(char c : buffer.array()) {
			
			if(extraChars.indexOf(c) != -1) {
				sb.append(def);
				continue;
			}
			
			// Control characters and whitespace are converted to space for all OS.
			// We do not check for the file separator character because it is included in each OS list of reserved characters.
			if(Character.getType(c) == Character.CONTROL || Character.isWhitespace(c)) {
				sb.append(def);
				continue;
			}


			if(targetOS == OperatingSystem.All || targetOS == OperatingSystem.Windows) {
				if(StringValidityChecker.isWindowsReservedPrintableFilenameCharacter(c)) {
					sb.append(def);
					continue;
				}
			}

			if(targetOS == OperatingSystem.All || targetOS == OperatingSystem.MacOS) {
				if(StringValidityChecker.isMacOSReservedPrintableFilenameCharacter(c)) {
					sb.append(def);
					continue;
				}
			}
			
			if(targetOS == OperatingSystem.All || targetOS == OperatingSystem.GenericUnix || targetOS == OperatingSystem.Linux || targetOS == OperatingSystem.FreeBSD) {
				if(StringValidityChecker.isUnixReservedPrintableFilenameCharacter(c)) {
					sb.append(def);
					continue;
				}
			}
			
			// Nothing did continue; so the character is okay
			sb.append(c);
		}

		// In windows, the last character of a filename may not be space or dot. We cut them off
		if(targetOS == OperatingSystem.All || targetOS == OperatingSystem.Windows) {
			int lastCharIndex = sb.length() - 1;
			while(lastCharIndex >= 0) {
				char lastChar = sb.charAt(lastCharIndex);
				if(lastChar == ' ' ||  lastChar == '.')
					sb.deleteCharAt(lastCharIndex--);
				else
					break;
			}
		}

		// Now the filename might be one of the reserved filenames in Windows (CON etc.) and we must replace it if it is...
		if(targetOS == OperatingSystem.All || targetOS == OperatingSystem.Windows) {
			if(StringValidityChecker.isWindowsReservedFilename(sb.toString()))
				sb.insert(0, '_');
		}

		if(sb.length() == 0) {
			sb.append("Invalid filename"); // TODO: L10n
		}

		return sb.toString();
	}

	public static String sanitize(String fileName) {
		return sanitizeFileName(fileName, detectedOS, "");
	}

	public static String sanitizeFileNameWithExtras(String fileName, String extraChars) {
		return sanitizeFileName(fileName, detectedOS, extraChars);
	}


	public static String sanitize(String filename, String mimeType) {
		filename = sanitize(filename);
		if(mimeType == null) return filename;
		if(filename.indexOf('.') >= 0) {
			String oldExt = filename.substring(filename.lastIndexOf('.'));
			if(DefaultMIMETypes.isValidExt(mimeType, oldExt)) return filename;
		}
		String defaultExt = DefaultMIMETypes.getExtension(filename);
		if(defaultExt == null) return filename;
		else return filename + '.' + defaultExt;
	}

	/**
	 * Find the length of an input stream. This method will consume the complete
	 * input stream until its {@link InputStream#read(byte[])} method returns
	 * <code>-1</code>, thus signalling the end of the stream.
	 *
	 * @param source
	 *            The input stream to find the length of
	 * @return The numbe of bytes that can be read from the stream
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static long findLength(InputStream source) throws IOException {
		long length = 0;
		byte[] buffer = new byte[BUFFER_SIZE];
		int read = 0;
		while (read > -1) {
			read = source.read(buffer);
			if (read != -1) {
				length += read;
			}
		}
		return length;
	}

	/**
	 * Copies <code>length</code> bytes from the source input stream to the
	 * destination output stream. If <code>length</code> is <code>-1</code>
	 * as much bytes as possible will be copied (i.e. until
	 * {@link InputStream#read()} returns <code>-1</code> to signal the end of
	 * the stream).
	 *
	 * @param source
	 *            The input stream to read from
	 * @param destination
	 *            The output stream to write to
	 * @param length
	 *            The number of bytes to copy
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static void copy(InputStream source, OutputStream destination, long length) throws IOException {
		long remaining = length;
		byte[] buffer = new byte[BUFFER_SIZE];
		int read = 0;
		while ((remaining == -1) || (remaining > 0)) {
			read = source.read(buffer, 0, ((remaining > BUFFER_SIZE) || (remaining == -1)) ? BUFFER_SIZE : (int) remaining);
			if (read == -1) {
				if (length == -1) {
					return;
				}
				throw new EOFException("stream reached eof");
			}
			destination.write(buffer, 0, read);
			if (remaining > 0)
				remaining -= read;
		}
	}
	
	public static boolean secureDeleteAll(File wd, Random random) throws IOException {
		if(!wd.isDirectory()) {
			System.err.println("DELETING FILE "+wd);
			try {
				secureDelete(wd, random);
			} catch (IOException e) {
				Logger.error(FileUtil.class, "Could not delete file: "+wd, e);
				return false;
			}
		} else {
			File[] subfiles = wd.listFiles();
			for(int i=0;i<subfiles.length;i++) {
				if(!removeAll(subfiles[i])) return false;
			}
			if(!wd.delete()) {
				Logger.error(FileUtil.class, "Could not delete directory: "+wd);
			}
		}
		return true;
	}


	/** Delete everything in a directory. Only use this when we are *very sure* there is no
	 * important data below it! */
	public static boolean removeAll(File wd) {
		if(!wd.isDirectory()) {
			System.err.println("DELETING FILE "+wd);
			if(!wd.delete() && wd.exists()) {
				Logger.error(FileUtil.class, "Could not delete file: "+wd);
				return false;
			}
		} else {
			File[] subfiles = wd.listFiles();
			for(int i=0;i<subfiles.length;i++) {
				if(!removeAll(subfiles[i])) return false;
			}
			if(!wd.delete()) {
				Logger.error(FileUtil.class, "Could not delete directory: "+wd);
			}
		}
		return true;
	}

	public static void secureDelete(File file, Random random) throws IOException {
		// FIXME somebody who understands these things should have a look at this...
		if(!file.exists()) return;
		long size = file.length();
		if(size > 0) {
			RandomAccessFile raf = null;
			try {
				System.out.println("Securely deleting "+file+" which is of length "+size+" bytes...");
				raf = new RandomAccessFile(file, "rw");
				raf.seek(0);
				long count;
				byte[] buf = new byte[4096];
				// First zero it out
				count = 0;
				while(count < size) {
					int written = (int) Math.min(buf.length, size - count);
					raf.write(buf, 0, written);
					count += written;
				}
				raf.getFD().sync();
				// Then ffffff it out
				for(int i=0;i<buf.length;i++)
					buf[i] = (byte)0xFF;
				raf.seek(0);
				count = 0;
				while(count < size) {
					int written = (int) Math.min(buf.length, size - count);
					raf.write(buf, 0, written);
					count += written;
				}
				raf.getFD().sync();
				// Then random data
				random.nextBytes(buf);
				raf.seek(0);
				count = 0;
				while(count < size) {
					int written = (int) Math.min(buf.length, size - count);
					raf.write(buf, 0, written);
					count += written;
				}
				raf.getFD().sync();
				raf.seek(0);
				// Then 0's again
				for(int i=0;i<buf.length;i++)
					buf[i] = 0;
				count = 0;
				while(count < size) {
					int written = (int) Math.min(buf.length, size - count);
					raf.write(buf, 0, written);
					count += written;
				}
				raf.getFD().sync();
				raf.close();
				raf = null;
			} finally {
				Closer.close(raf);
			}
		}
		if((!file.delete()) && file.exists())
			throw new IOException("Unable to delete file "+file);
	}

	public static long getFreeSpace(File dir) {
		// Use JNI to find out the free space on this partition.
		long freeSpace = -1;
		try {
			Class<? extends File> c = dir.getClass();
			Method m = c.getDeclaredMethod("getFreeSpace", new Class<?>[0]);
			if(m != null) {
				Long lFreeSpace = (Long) m.invoke(dir, new Object[0]);
				if(lFreeSpace != null) {
					freeSpace = lFreeSpace.longValue();
					System.err.println("Found free space on node's partition: on " + dir + " = " + SizeUtil.formatSize(freeSpace));
				}
			}
		} catch(NoSuchMethodException e) {
			// Ignore
			freeSpace = -1;
		} catch(Throwable t) {
			System.err.println("Trying to access 1.6 getFreeSpace(), caught " + t);
			freeSpace = -1;
		}
		return freeSpace;
	}

	/**
	** Set owner-only RW on the given file.
	*/
	public static boolean setOwnerRW(File f) {
		return setOwnerPerm(f, true, true, false);
	}

	/**
	** Set owner-only RWX on the given file.
	*/
	public static boolean setOwnerRWX(File f) {
		return setOwnerPerm(f, true, true, true);
	}

	/**
	** Set owner-only permissions on the given file.
	*/
	public static boolean setOwnerPerm(File f, boolean r, boolean w, boolean x) {
		/* JDK6 replace when we upgrade
		boolean b = f.setReadable(false, false);
		b &= f.setWritable(false, false);
		b &= f.setExecutable(false, false);
		b &= f.setReadable(r, true);
		b &= f.setWritable(w, true);
		b &= f.setExecutable(x, true);
		return b;
		*/

		boolean success = true;
		try {

			String[] methods = {"setReadable", "setWritable", "setExecutable"};
			boolean[] perms = {r, w, x};

			for (int i=0; i<methods.length; ++i) {
				Method m = File.class.getDeclaredMethod(methods[i], boolean.class, boolean.class);
				if (m != null) {
					success &= (Boolean)m.invoke(f, false, false);
					success &= (Boolean)m.invoke(f, perms[i], true);
				}
			}

		} catch (NoSuchMethodException e) {
			success = false;
		} catch (java.lang.reflect.InvocationTargetException e) {
			success = false;
		} catch (IllegalAccessException e) {
			success = false;
		} catch (ExceptionInInitializerError e) {
			success = false;
		} catch (RuntimeException e) {
			success = false;
		}
		return success;
	}

	public static boolean equals(File a, File b) {
		a = getCanonicalFile(a);
		b = getCanonicalFile(b);
		return a.equals(b);
	}

}
