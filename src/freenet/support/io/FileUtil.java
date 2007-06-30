/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.client.DefaultMIMETypes;

final public class FileUtil {

	/** Round up a value to the next multiple of a power of 2 */
	private static final long roundup_2n (long val, int blocksize) {
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
		File result;
		try {
			result = file.getCanonicalFile();
		} catch (IOException e) {
			result = file.getAbsoluteFile();
		}
		return result;
	}
	
	// FIXME: this is called readUTF but it reads in the default charset ... eh??
	public static String readUTF(File file) throws FileNotFoundException, IOException {
		StringBuffer result = new StringBuffer();
		FileInputStream fis = null;
		BufferedInputStream bis = null;
		InputStreamReader isr = null;
		
		try {
			fis = new FileInputStream(file);
			bis = new BufferedInputStream(fis);
			isr = new InputStreamReader(bis);

			char[] buf = new char[4096];
			int length = 0;

			while((length = isr.read(buf)) > 0) {
				result.append(buf, 0, length);
			}

		} finally {
			try {
				if(isr != null) isr.close();
				if(bis != null) bis.close();
				if(fis != null) fis.close();
			} catch (IOException e) {}
		}
		return result.toString();
	}

	public static String sanitize(String s) {
		StringBuffer sb = new StringBuffer(s.length());
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if((c == '/') || (c == '\\') || (c == '%') || (c == '>') || (c == '<') || (c == ':') || (c == '\'') || (c == '\"'))
				continue;
			if(Character.isDigit(c))
				sb.append(c);
			else if(Character.isLetter(c))
				sb.append(c);
			else if(Character.isWhitespace(c))
				sb.append(' ');
			else if((c == '-') || (c == '_') || (c == '.'))
				sb.append(c);
		}
		return sb.toString();
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
	
}
