package freenet.client.filter;

import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;
import freenet.support.io.NullBucket;
import freenet.test.PngUtil;
import freenet.test.PngUtil.Chunk;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import org.junit.rules.TemporaryFolder;

import static freenet.client.filter.ResourceFileUtil.resourceToBucket;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PNGFilterTest {
	protected static Object[][] testImages = {
			// { image file, valid }
	        // NOT PASS  { "./png/broken/scal_floating_point.png", false }, //
	        // NOT PASS  { "./png/broken/splt_length_mod_10.png", false }, //
	        // NOT PASS  { "./png/broken/length_ster.png", false }, //
	        // NOT PASS  { "./png/broken/scal_unit_specifier.png", false }, //
	        { "./png/broken/nonconsecutive_idat.png", false }, //
	        // NOT PASS  { "./png/broken/plte_too_many_entries.png", false }, //
	        // NOT PASS  { "./png/broken/private_filter_method.png", false }, //
	        // NOT PASS  { "./png/broken/truncate_idat_1.png", false }, //
	        // NOT PASS  { "./png/broken/length_iend.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_bit_depth.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_scal.png", false }, //
	        // NOT PASS  { "./png/broken/chunk_type.png", false }, //
	        // NOT PASS  { "./png/broken/plte_too_many_entries_2.png", false }, //
	        // NOT PASS  { "./png/broken/length_offs.png", false }, //
	        // NOT PASS  { "./png/broken/truncate_idat_0.png", false }, //
	        // NOT PASS  { "./png/broken/length_gama.png", false }, //
	        { "./png/broken/truncate_zlib_2.png", false }, //
	        // NOT PASS  { "./png/broken/private_filter_type.png", false }, //
	        // NOT PASS  { "./png/broken/sbit_after_plte.png", false }, //
	        // NOT PASS  { "./png/broken/missing_idat.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_filter_method.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_compression_method.png", false }, //
	        // NOT PASS  { "./png/broken/pcal_after_idat.png", false }, //
	        { "./png/broken/plte_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/chunk_private_critical.png", false }, //
	        // NOT PASS  { "./png/broken/splt_duplicate_name.png", false }, //
	        { "./png/broken/chunk_length.png", false }, //
	        // NOT PASS  { "./png/broken/scal_after_idat.png", false }, //
	        { "./png/broken/chunk_crc.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_interlace_method.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_1bit_alpha.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_color_type.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_plte.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_ster.png", false }, //
	        // NOT PASS  { "./png/broken/length_sbit.png", false }, //
	        // NOT PASS  { "./png/broken/splt_length_mod_6.png", false }, //
	        // NOT PASS  { "./png/broken/length_sbit_2.png", false }, //
	        // NOT PASS  { "./png/broken/trns_bad_color_type.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_gama.png", false }, //
	        // NOT PASS  { "./png/broken/offs_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/length_ihdr.png", false }, //
	        { "./png/broken/missing_ihdr.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_image_size.png", false }, //
	        // NOT PASS  { "./png/broken/gama_after_plte.png", false }, //
	        { "./png/broken/multiple_ihdr.png", false }, //
	        // NOT PASS  { "./png/broken/unknown_filter_type.png", false }, //
	        // NOT PASS  { "./png/broken/scal_zero.png", false }, //
	        { "./png/broken/truncate_zlib.png", false }, //
	        // NOT PASS  { "./png/broken/scal_negative.png", false }, //
	        // NOT PASS  { "./png/broken/ster_mode.png", false }, //
	        // NOT PASS  { "./png/broken/private_interlace_method.png", false }, //
	        // NOT PASS  { "./png/broken/srgb_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/ster_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/ihdr_16bit_palette.png", false }, //
	        // NOT PASS  { "./png/broken/iccp_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/plte_empty.png", false }, //
	        // NOT PASS  { "./png/broken/private_compression_method.png", false }, //
	        // NOT PASS  { "./png/broken/offs_unit_specifier.png", false }, //
	        // NOT PASS  { "./png/broken/plte_length_mod_three.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_offs.png", false }, //
	        // NOT PASS  { "./png/broken/gama_after_idat.png", false }, //
	        // NOT PASS  { "./png/broken/missing_plte.png", false }, //
	        // NOT PASS  { "./png/broken/splt_sample_depth.png", false }, //
	        // NOT PASS  { "./png/broken/multiple_pcal.png", false }, //
	        // NOT PASS  { "./png/broken/plte_in_grayscale.png", false }, //
	        { "./png/misc/pngbar.png", true }, //
	        { "./png/misc/pngnow.png", true }, //
	        { "./png/misc/pngtest.png", true }, //
	        { "./png/suite/basn2c16.png", true }, //
	        { "./png/suite/basn3p01.png", true }, //
	        { "./png/suite/basn2c08.png", true }, //
	        { "./png/suite/basn3p04.png", true }, //
	        { "./png/suite/basn0g16.png", true }, //
	        { "./png/suite/basn0g08.png", true }, //
	        { "./png/suite/basn0g02.png", true }, //
	        { "./png/suite/basn4a08.png", true }, //
	        { "./png/suite/basn6a08.png", true }, //
	        { "./png/suite/basn6a16.png", true }, //
	        { "./png/suite/basn4a16.png", true }, //
	        { "./png/suite/basn0g01.png", true }, //
	        { "./png/suite/basn3p08.png", true }, //
	        { "./png/suite/basn3p02.png", true }, //
	        { "./png/suite/basn0g04.png", true }, //
	};

	@Test
	public void testSuiteTest() throws IOException {
		PNGFilter filter = new PNGFilter(false, false, true);

		for (Object[] test : testImages) {
			String filename = (String) test[0];
			boolean valid = (Boolean) test[1];
			Bucket ib;
			try {
				ib = resourceToBucket(filename);
			} catch (IOException e) {
				System.out.println(filename + " not found, test skipped");
				continue;
			}

			try {
				filter.readFilter(ib.getInputStream(), new NullBucket().getOutputStream(), "", null, null, null);

				assertTrue(filename + " should " + (valid ? "" : "not ") + "be valid", valid);
			} catch (DataFilterException dfe) {
				assertFalse(filename + " should " + (valid ? "" : "not ") + "be valid", valid);
			}
		}
	}

	@Test
	public void cICPChunkIsNotFiltered() throws IOException {
		writeChunksAndVerifyChunks(asList(cICPChunk), emptyList(), hasItem(cICPChunk));
	}

	@Test
	public void cICPChunkAfterPLTEChunkIsRemoved() throws IOException {
		writeChunksAndVerifyChunks(asList(PLTEChunk, cICPChunk), emptyList(), not(hasItem(cICPChunk)));
	}

	@Test
	public void cICPChunkAfterIDATChunkIsRemoved() throws IOException {
		writeChunksAndVerifyChunks(emptyList(), asList(cICPChunk), not(hasItem(cICPChunk)));
	}

	@Test
	public void mDCVChunkWithCICPChunkIsNotFiltered() throws IOException {
		writeChunksAndVerifyChunks(asList(cICPChunk, mDCVChunk), emptyList(), hasItem(mDCVChunk));
	}

	@Test
	public void mDCVChunkWithoutCICPChunkIsFiltered() throws IOException {
		writeChunksAndVerifyChunks(asList(mDCVChunk), emptyList(), not(hasItem(mDCVChunk)));
	}

	@Test
	public void mDCVChunkAfterPLTEChunkIsRemoved() throws IOException {
		writeChunksAndVerifyChunks(asList(cICPChunk, PLTEChunk, mDCVChunk), emptyList(), not(hasItem(mDCVChunk)));
	}

	@Test
	public void mDCVChunkAfterIDATChunkIsRemoved() throws IOException {
		writeChunksAndVerifyChunks(asList(cICPChunk), asList(mDCVChunk), not(hasItem(mDCVChunk)));
	}

	@Test
	public void cLLIChunkIsNotFiltered() throws IOException {
		writeChunksAndVerifyChunks(asList(new Chunk("cLLI", new byte[0])), emptyList(), hasItem(new Chunk("cLLI", new byte[0])));
	}

	private void writeChunksAndVerifyChunks(List<Chunk> preIDATChunks, List<Chunk> postIDATChunks, Matcher<Iterable<? super Chunk>> chunksVerifier) throws IOException {
		PNGFilter filter = new PNGFilter(false, false, true);
		File pngFile = temporaryFolder.newFile();
		PngUtil.createPngFile(pngFile, preIDATChunks, postIDATChunks);
		File filteredPngFile = temporaryFolder.newFile();
		Bucket bucket = new FileBucket(pngFile, true, false, false, false);
		try {
			try (FileOutputStream outputStream = new FileOutputStream(filteredPngFile)) {
				filter.readFilter(bucket.getInputStream(), outputStream, "", null, null, null);
			}
			assertThat(PngUtil.getChunks(filteredPngFile), chunksVerifier);
		} catch (DataFilterException e) {
			assertThat(emptyList(), chunksVerifier);
		}
	}

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private static final Chunk cICPChunk = new Chunk("cICP", new byte[0]);
	private static final Chunk PLTEChunk = new Chunk("PLTE", new byte[0]);
	private static final Chunk mDCVChunk = new Chunk("mDCV", new byte[0]);

}
