package freenet.support.compress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class SingleOffsetReplacingOutputStreamTest {

	@Test
	public void allBytesBeforeTheOffsetAreUnchanged() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(32768, 1, 65536);
	}

	@Test
	public void byteAtOffsetZeroCanBeReplaced() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(10, 1, 0);
	}

	@Test
	public void byteAtOffsetOneCanBeReplaced() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(10, 1, 1);
	}

	@Test
	public void byteAtEndOfBufferCanBeReplaced() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(10, 1, 9);
	}

	@Test
	public void byteAtBeginningOfSecondBufferCanBeReplaced() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(10, 2, 10);
	}

	@Test
	public void byteInTheMiddleOfSecondBufferCanBeReplaced() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingByteArrays(10, 3, 15);
	}

	@Test
	public void byteAtOffsetZeroCanBeReplacedWhenWritingSingleBytes() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingSingleBytes(4096, 16, 0);
	}

	@Test
	public void byteInMiddleOfStreamBeReplacedWhenWritingSingleBytes() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingSingleBytes(8192, 8, 12345);
	}

	@Test
	public void byteAtEndOfStreamCanBeReplacedWhenWritingSingleBytes() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingSingleBytes(16384, 4, 65535);
	}

	@Test
	public void byteAtStartOfBufferCanBeReplacedWhenWritingHalfBuffers() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingHalfBuffers(4096, 16, 0);
	}

	@Test
	public void byteAtMiddleOfBufferCanBeReplacedWhenWritingHalfBuffers() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingHalfBuffers(8192, 8, 12345);
	}

	@Test
	public void byteAtEndOfBufferCanBeReplacedWhenWritingHalfBuffers() throws IOException {
		filterOutputStreamWithOffsetAndReplacementWritingHalfBuffers(16384, 4, 65535);
	}

	private void filterOutputStreamWithOffsetAndReplacementWritingSingleBytes(int blockSize, int numberOfBlocks, int replacementOffset) throws IOException {
		filterOutputStreamWithOffsetAndReplacement(blockSize, numberOfBlocks, replacementOffset, (buffer, length, repetitions, outputStream) -> {
			for (int index = 0; index < length * repetitions; index++) {
				outputStream.write(buffer[index % length]);
			}
		});
	}

	private void filterOutputStreamWithOffsetAndReplacementWritingByteArrays(int blockSize, int numberOfBlocks, int replacementOffset) throws IOException {
		filterOutputStreamWithOffsetAndReplacement(blockSize, numberOfBlocks, replacementOffset, (buffer, length, repetitions, outputStream) -> {
			for (int blockIndex = 0; blockIndex < repetitions; blockIndex++) {
				outputStream.write(buffer, 0, length);
			}
		});
	}

	private void filterOutputStreamWithOffsetAndReplacementWritingHalfBuffers(int blockSize, int numberOfBlocks, int replacementOffset) throws IOException {
		filterOutputStreamWithOffsetAndReplacement(blockSize, numberOfBlocks, replacementOffset, ((buffer, length, repetitions, outputStream) -> {
			for (int blockIndex = 0; blockIndex < repetitions; blockIndex++) {
				outputStream.write(buffer, 0, length / 2);
				outputStream.write(buffer, length / 2, length - (length / 2));
			}
		}));
	}

	private void filterOutputStreamWithOffsetAndReplacement(int blockSize, int numberOfBlocks, int replacementOffset, BufferToOutputStreamCopierStrategy bufferToOutputStreamCopierStrategy) throws IOException {
		byte[] bufferToWrite = new byte[blockSize];
		new Random().nextBytes(bufferToWrite);
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		SingleOffsetReplacingOutputStream outputStream = new SingleOffsetReplacingOutputStream(byteArrayOutputStream, replacementOffset, (byte) (bufferToWrite[replacementOffset % blockSize] ^ 0xff));
		bufferToOutputStreamCopierStrategy.copyBufferToOutput(bufferToWrite, blockSize, numberOfBlocks, outputStream);
		byte[] writtenBuffer = byteArrayOutputStream.toByteArray();
		for (int offset = 0; offset < blockSize * numberOfBlocks; offset++) {
			assertThat("offset " + offset, writtenBuffer[offset], equalTo((byte) (bufferToWrite[offset % blockSize] ^ ((offset == replacementOffset) ? 0xff : 0x00))));
		}
	}

	@FunctionalInterface
	private interface BufferToOutputStreamCopierStrategy {
		void copyBufferToOutput(byte[] buffer, int length, int repetitions, OutputStream outputStream) throws IOException;
	}

}
