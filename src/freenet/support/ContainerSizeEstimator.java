/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.HashMap;
import java.util.Map;

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.ManifestElement;

/**
 * Helper class to estaminate the container size,
 * @author saces
 *
 */
public final class ContainerSizeEstimator {

	public static final ARCHIVE_TYPE DEFAULT_ARCHIVE_TYPE = ARCHIVE_TYPE.TAR;

	public final static class ContainerSize {

		private long _sizeFiles;
		private long _sizeFilesNoLimit;
		private long _sizeSubTrees;
		private long _sizeSubTreesNoLimit;

		private ContainerSize() {
			_sizeFiles = 0;
			_sizeFilesNoLimit = 0;
			_sizeSubTrees = 0;
			_sizeSubTreesNoLimit = 0;
		}

		public long getSizeTotal() {
			return _sizeFiles+_sizeSubTrees;
		}

		public long getSizeTotalNoLimit() {
			return _sizeFilesNoLimit+_sizeSubTreesNoLimit;
		}

		public long getSizeFiles() {
			return _sizeFiles;
		}

		public long getSizeFilesNoLimit() {
			return _sizeFilesNoLimit;
		}

		public long getSizeSubTrees() {
			return _sizeSubTrees;
		}

		public long getSizeSubTreesNoLimit() {
			return _sizeSubTreesNoLimit;
		}
	}

	private ContainerSizeEstimator() {}

	public static ContainerSize getSubTreeSize(HashMap<String, Object> metadata, long maxItemSize, long maxContainerSize, int maxDeep) {
		ContainerSize result = new ContainerSize();
		getSubTreeSize(metadata, result, maxItemSize, maxContainerSize, maxDeep);
		return result;
	}

	private static void getSubTreeSize(HashMap<String, Object> metadata, ContainerSize result, long maxItemSize, long maxContainerSize,int maxDeep) {
		// files
		for(Map.Entry<String,Object> entry:metadata.entrySet()) {
			Object o = entry.getValue();
			if (o instanceof ManifestElement) {
				ManifestElement me = (ManifestElement)o;
				long itemsize = me.getSize();
				if (itemsize > -1) {
					result._sizeFilesNoLimit += getContainerItemSize(me.getSize());
					// Add some bytes for .metadata element.
					// FIXME 128 picked out of the air! Look up the format.
					result._sizeFilesNoLimit += 128 + me.getName().length();
					if (itemsize > maxItemSize)
						result._sizeFiles += 512;  // spare for redirect
					else {
						result._sizeFiles += getContainerItemSize(me.getSize());
						// Add some bytes for .metadata element.
						// FIXME 128 picked out of the air! Look up the format.
						result._sizeFilesNoLimit += 128 + me.getName().length();
						// FIXME The tar file will need the full name????
					}
					if (result._sizeFiles > maxContainerSize) break;
				} else {
					// Redirect.
					result._sizeFiles += 512;
					result._sizeFilesNoLimit += 512;
				}
			}
		}
		// sub dirs
		if (maxDeep > 0) {
			for(Map.Entry<String,Object> entry:metadata.entrySet()) {
				Object o = entry.getValue();
				if (o instanceof HashMap) {
					result._sizeSubTrees += 512;
					@SuppressWarnings("unchecked")
					HashMap<String, Object> hm = (HashMap<String, Object>) o;
					ContainerSize tempResult = new ContainerSize();
					getSubTreeSize(hm, tempResult, maxItemSize, (maxContainerSize-result._sizeSubTrees), maxDeep-1);
					result._sizeSubTrees += tempResult.getSizeTotal();
					result._sizeSubTreesNoLimit += tempResult.getSizeTotalNoLimit();
					if (result._sizeSubTrees > maxContainerSize) break;
				}
			}
		}
	}

	public static long getContainerItemSize(long size) {
		return getContainerItemSize(DEFAULT_ARCHIVE_TYPE, size);
	}

	private static long getContainerItemSize(ARCHIVE_TYPE archiveType, long size) {
		if (archiveType == ARCHIVE_TYPE.TAR)
			return tarItemSize(size);
		throw new UnsupportedOperationException("TODO, only TAR supportet atm.");
	}

	public static long tarItemSize(long size) {
		return 512 + (((size + 511) / 512) * 512);
	}
}
