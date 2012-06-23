package freenet.keys;

import java.net.MalformedURLException;

import junit.framework.TestCase;

public class FreenetURITest extends TestCase {
	// Some URI for wAnnA? index
	private static final String WANNA_USK_1 = "USK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search/17/index_d51.xml";
	private static final String WANNA_SSK_1 = "SSK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search-17/index_d51.xml";
	private static final String WANNA_CHK_1 = "CHK@DTCDUmnkKFlrJi9UlDDVqXlktsIXvAJ~ZTseyx5cAZs,PmA2rLgWZKVyMXxSn-ZihSskPYDTY19uhrMwqDV-~Sk,AAICAAI/index_d51.xml";

	public void testSskForUSK() throws MalformedURLException {
		FreenetURI uri1 = new FreenetURI(WANNA_USK_1);
		FreenetURI uri2 = new FreenetURI(WANNA_SSK_1);

		assertEquals(uri2, uri1.sskForUSK());
		assertEquals(uri1, uri2.uskForSSK());

		try {
			uri1.uskForSSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}
		try {
			uri2.sskForUSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}

		try {
			new FreenetURI(WANNA_CHK_1).sskForUSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}
		try {
			new FreenetURI(WANNA_CHK_1).uskForSSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}
		try {
			new FreenetURI(
			        "SSK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search-17XXXX/index_d51.xml")
			        .sskForUSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}
		try {
			new FreenetURI(
			        "SSK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search17/index_d51.xml")
			        .sskForUSK();
			fail("no exception throw!");
		} catch (IllegalStateException e) {
			// pass
		}
	}
	
}
