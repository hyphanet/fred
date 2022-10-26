package freenet.clients.http;

public class RssSniffer {

	/**
	 * Look for any of the following strings as top-level XML tags: &lt;rss &lt;feed &lt;rdf:RDF
	 *
	 * If they start at the beginning of the file, or are preceded by one or more &lt;! or &lt;?
	 * tags, then firefox will read it as RSS. In which case we must force it to be downloaded to
	 * disk.
	 */
	public static boolean isSniffedAsFeed(byte[] prefix) {
		int tlt = indexOfTopLevelTag(prefix);
		if (tlt == -1) {
			return false;
		}
		return startsWithString(prefix, "<rss", tlt) ||
				       startsWithString(prefix, "<feed", tlt) ||
				       startsWithString(prefix, "<rdf:RDF", tlt);
	}

	/**
	 * Finds the smallest index of a top-level XML tag in the data. A tag is any sequence that
	 * starts with '<'. A top-level tag is any subsequence that is not a comment, processing
	 * instruction or doctype (e.g. "<?" or "<!") and is not embedded in such a tag. Returns -1 if
	 * no such tag exists in the data.
	 */
	private static int indexOfTopLevelTag(byte[] data) {
		int i = 0;
		// Scan over all tags in the data.
		while ((i = indexOf(data, (byte) '<', i)) != -1) {
			i++;
			if (i >= data.length) {
				return -1;
			}
			if (data[i] != (byte) '?' && data[i] != (byte) '!') {
				// Found a top-level tag.
				return i - 1;
			}
			// Found a comment, processing instruction or doctype; proceed to the end of the tag.
			i = indexOf(data, (byte) '>', i);
			if (i == -1) {
				// No end of tag in buffer: we won't find a top-level tag.
				return -1;
			}
		}
		return -1;
	}

	/**
	 * Checks whether the given data starts with the characters in the key value, when starting at
	 * fromIndex in the data array.
	 */
	private static boolean startsWithString(byte[] data, String key, int fromIndex) {
		if (data.length - fromIndex < key.length()) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			if (data[i + fromIndex] != key.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the smallest index of the key in the data array not smaller than fromIndex, or -1 if
	 * no such index exists.
	 */
	private static int indexOf(byte[] data, byte key, int fromIndex) {
		for (int i = fromIndex; i < data.length; i++) {
			if (data[i] == key) {
				return i;
			}
		}
		return -1;
	}

}
