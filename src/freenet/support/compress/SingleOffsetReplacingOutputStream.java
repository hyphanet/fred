package freenet.support.compress;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A {@link FilterOutputStream} that replaces a single byte at a specific
 * offset when being written to. It can be used to change the operating system
 * byte in the header of a {@link GZIPOutputStream}.
 * <p>
 * This class is not safe for usage from multiple threads.
 */
public class SingleOffsetReplacingOutputStream extends FilterOutputStream {

	private final int replacementOffset;
	private final int replacementValue;
	private int currentOffset = 0;

	public SingleOffsetReplacingOutputStream(OutputStream outputStream, int replacementOffset, int replacementValue) {
		super(outputStream);
		this.replacementOffset = replacementOffset;
		this.replacementValue = replacementValue;
	}

	@Override
	public void write(int b) throws IOException {
		if (currentOffset == replacementOffset) {
			out.write(replacementValue);
		} else {
			out.write(b);
		}
		currentOffset++;
	}

	@Override
	public void write(byte[] buffer, int offset, int length) throws IOException {
		if (offsetToReplaceIsInBufferBeingWritten(length)) {
			out.write(buffer, offset, replacementOffset - currentOffset);
			out.write(replacementValue);
			out.write(buffer, offset + (replacementOffset - currentOffset) + 1, length - (replacementOffset - currentOffset) - 1);
		} else {
			out.write(buffer, offset, length);
		}
		currentOffset += length;
	}

	private boolean offsetToReplaceIsInBufferBeingWritten(int length) {
		return (currentOffset <= replacementOffset) && ((currentOffset + length) > replacementOffset);
	}

}
