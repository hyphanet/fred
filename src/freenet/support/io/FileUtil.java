/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.File;
import java.io.IOException;

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

	/** Is possParent a parent of filename?
	 * FIXME Move somewhere generic. 
	 * Why doesn't java provide this? :( */
	public static boolean isParent(File possParent, File filename) {
		File canonParent;
		File canonFile;
		try {
			canonParent = possParent.getCanonicalFile();
		} catch (IOException e) {
			canonParent = possParent.getAbsoluteFile();
		}
		try {
			canonFile = filename.getCanonicalFile();
		} catch (IOException e) {
			canonFile = filename.getAbsoluteFile();
		}
		if(isParentInner(possParent, filename)) return true;
		if(isParentInner(possParent, canonFile)) return true;
		if(isParentInner(canonParent, filename)) return true;
		if(isParentInner(canonParent, canonFile)) return true;
		return false;
	}

	private static boolean isParentInner(File possParent, File filename) {
		while(true) {
			if(filename.equals(possParent)) return true;
			filename = filename.getParentFile();
			if(filename == null) return false;
		}
	}
}
