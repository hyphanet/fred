package freenet.support.io;

import java.io.File;

public class FileUtil {

	/**
	 * Guesstimate real disk usage for a file with a given filename, of a given length.
	 */
	public static long estimateUsage(File file, long l) {
		/**
		 * It's possible that none of these assumptions are accurate for any filesystem;
		 * this is intended to be a plausible worst case.
		 */
		// Assume 4kB clusters for calculating block usage (NTFS)
		long blockUsage = ((l / 4096) + (l % 4096 > 0 ? 1 : 0)) * 4096;
		// Assume 512 byte filename entries, with 100 bytes overhead, for filename overhead (NTFS)
		String filename = file.getName();
		int nameLength = filename.getBytes().length + 100;
		long filenameUsage = ((nameLength / 4096) + (nameLength % 4096 > 0 ? 1 : 0)) * 4096;
		// Assume 50 bytes per block tree overhead with 1kB blocks (reiser3 worst case)
		long extra = ((l / 1024) + (l % 1024 > 0 ? 1 : 0) + 1) * 50;
		return blockUsage + filenameUsage + extra;
	}
}
