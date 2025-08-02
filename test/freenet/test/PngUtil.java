package freenet.test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

import static java.util.Collections.emptyList;

/**
 * Utility class for creating PNG files for tests.
 * <h2>Creating Basic PNG Files</h2>
 * <p>
 * In order to create a basic PNG file, don’t specify extra chunks:
 * </p>
 * <pre>
 * PngUtil.createPngFile(pngFile, emptyList());
 * </pre>
 * <p>
 * Extra chunks are inserted before the first {@code IDAT} chunk. The chunk’s
 * CRC will be calculated automatically, if omitted.
 * </p>
 * <pre>
 * PngUtil.createPngFile(pngFile, asList(new Chunk("tEST", new byte[0]));
 * </pre>
 * <h2>Verifying Chunks of a PNG File</h2>
 * <p>
 * The list of chunks in a PNG file can be read by
 * {@link PngUtil#getChunks(File)}, and verified using Chunk equality.
 * </p>
 * <pre>
 * assertThat(PngUtil.getChunks(pngFile), hasItem(new Chunk("tEST", new byte[0])));
 * </pre>
 * If more detailed tests are required (such as “needs to contain at least 15
 * bytes”), a custom matcher needs to be written.
 */
public class PngUtil {

	private static final byte[] pngHeader = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

	/**
	 * Returns the chunks from a PNG file. If the given file is not a PNG
	 * (i.e. it does not have the PNG header), an empty list is returned.
	 * <p>
	 * This method should only be used on valid PNGs, because weird things
	 * (like exhausted memory, or exceptions) might happen otherwise.
	 * </p>
	 *
	 * @param pngFile The PNG file to list chunks of
	 * @return The chunks found in the given file, or an empty list
	 * @throws IOException if an I/O error occurs
	 */
	public static List<Chunk> getChunks(File pngFile) throws IOException {
		List<Chunk> chunks = new ArrayList<>();
		try (FileInputStream fileInputStream = new FileInputStream(pngFile);
			 DataInputStream dataInputStream = new DataInputStream(fileInputStream)) {
			byte[] headerBuffer = new byte[8];
			dataInputStream.readFully(headerBuffer);
			if (!Arrays.equals(headerBuffer, pngHeader)) {
				return emptyList();
			}
			while (true) {
				try {
					int length = dataInputStream.readInt();
					byte[] type = new byte[4];
					dataInputStream.readFully(type);
					byte[] data = new byte[length];
					dataInputStream.readFully(data);
					int crc = dataInputStream.readInt();
					chunks.add(new Chunk(new String(type, StandardCharsets.UTF_8), data, crc));
				} catch (EOFException e) {
					break;
				}
			}
		}
		return chunks;
	}

	/**
	 * Writes a 1x1 transparent PNG and allows adding extra chunks before the
	 * IDAT chunk.
	 *
	 * @param destination The file to write
	 * @param extraChunks The extra chunks to write
	 * @throws IOException if an I/O error occurs
	 */
	public static void createPngFile(File destination, List<Chunk> extraChunks) throws IOException {
		try (FileOutputStream fileOutputStream = new FileOutputStream(destination)) {
			fileOutputStream.write(pngHeader);
			new Chunk("IHDR", new byte[] { /* width */ 0, 0, 0, 1, /* height */ 0, 0, 0, 1, /* bit depth */ 8, /* color type greyscale */ 6, 0, 0, 0 }).write(fileOutputStream);
			for (Chunk extraChunk : extraChunks) {
				extraChunk.write(fileOutputStream);
			}
			new Chunk("IDAT", new byte[] { 0x08, 0x5b, 0x63, 0x60, 0x00, 0x02, 0x00, 0x00, 0x05, 0x00, 0x01 }).write(fileOutputStream);
			new Chunk("IEND", new byte[0]).write(fileOutputStream);
		}
	}

	/**
	 * Represents a PNG chunk.
	 */
	public static class Chunk {

		/**
		 * Creates a new chunk.
		 *
		 * @param type The chunk type; must follow all rules for valid chunk
		 * types!
		 * @param data The data contained in the chunk
		 */
		public Chunk(String type, byte[] data) {
			this(type, data, getCrc(type, data));
		}

		/**
		 * Creates a new chunk.
		 *
		 * @param type The chunk type; must follow all rules for valid chunk
		 * types!
		 * @param data The data contained in the chunk
		 * @param crc The CRC of the chunk
		 */
		public Chunk(String type, byte[] data, int crc) {
			this.type = type;
			this.data = data;
			this.crc = crc;
		}

		/**
		 * Writes this chunk to the given output stream.
		 *
		 * @param outputStream The output stream to write this chunk to
		 */
		public void write(OutputStream outputStream) throws IOException {
			outputStream.write(ByteBuffer.allocate(4).putInt(data.length).array());
			outputStream.write(type.getBytes(StandardCharsets.UTF_8));
			outputStream.write(data);
			outputStream.write(ByteBuffer.allocate(4).putInt(crc).array());
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Chunk)) {
				return false;
			}
			Chunk chunk = (Chunk) o;
			return crc == chunk.crc && Objects.equals(type, chunk.type) && Objects.deepEquals(data, chunk.data);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, Arrays.hashCode(data), crc);
		}

		@Override
		public String toString() {
			return String.format("%s: %d, %08x", type, data.length, crc);
		}

		/**
		 * Calculates the CRC for the given chunk type and data.
		 *
		 * @param type The type of the chunk
		 * @param data The data of the chunk
		 * @return The calculated CRC
		 */
		private static int getCrc(String type, byte[] data) {
			CRC32 crc = new CRC32();
			crc.update(type.getBytes(StandardCharsets.UTF_8));
			crc.update(data);
			return (int) crc.getValue();
		}

		private final String type;
		private final byte[] data;
		private final int crc;

	}

}
