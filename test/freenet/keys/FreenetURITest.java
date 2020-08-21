package freenet.keys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.MalformedURLException;

import org.junit.Test;

public class FreenetURITest {
	// Some URI for wAnnA? index
	private static final String WANNA_USK_1 = "USK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search/17/index_d51.xml";
	private static final String WANNA_SSK_1 = "SSK@5hH~39FtjA7A9~VXWtBKI~prUDTuJZURudDG0xFn3KA,GDgRGt5f6xqbmo-WraQtU54x4H~871Sho9Hz6hC-0RA,AQACAAE/Search-17/index_d51.xml";
	private static final String WANNA_CHK_1 = "CHK@DTCDUmnkKFlrJi9UlDDVqXlktsIXvAJ~ZTseyx5cAZs,PmA2rLgWZKVyMXxSn-ZihSskPYDTY19uhrMwqDV-~Sk,AAICAAI/index_d51.xml";

	@Test
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

	@Test
	public void testDeriveRequestURIFromInsertURI() throws MalformedURLException {
		final FreenetURI chk = new FreenetURI("CHK@DTCDUmnkKFlrJi9UlDDVqXlktsIXvAJ~ZTseyx5cAZs,PmA2rLgWZKVyMXxSn-ZihSskPYDTY19uhrMwqDV-~Sk,AAICAAI/index_d51.xml");
		assertEquals(chk, chk.deriveRequestURIFromInsertURI());
		
		final FreenetURI ksk = new FreenetURI("KSK@test");
		assertEquals(ksk, ksk.deriveRequestURIFromInsertURI());
		
		final FreenetURI requestUriUSK = new FreenetURI("USK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust/5");
		final FreenetURI insertUriUSK = new FreenetURI("USK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust/5");
		assertEquals(requestUriUSK, insertUriUSK.deriveRequestURIFromInsertURI());
		
		try {
			requestUriUSK.deriveRequestURIFromInsertURI();
			fail("requestUriUSK.deriveRequestURIFromInsertURI() should fail because it IS a request URI already!");
		} catch(MalformedURLException e) {
			// Success
		}
		
		final FreenetURI requestUriSSK = new FreenetURI("SSK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/WebOfTrust-5");
		final FreenetURI insertUriSSK = new FreenetURI("SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/WebOfTrust-5");
		assertEquals(requestUriSSK, insertUriSSK.deriveRequestURIFromInsertURI());
		
		try {
			requestUriSSK.deriveRequestURIFromInsertURI();
			fail("requestUriSSK.deriveRequestURIFromInsertURI() should fail because it IS a request URI already!");
		} catch(MalformedURLException e) {
			// Success
		}
		
        final FreenetURI requestUriSSKPlain = new FreenetURI("SSK@sdFxM0Z4zx4-gXhGwzXAVYvOUi6NRfdGbyJa797bNAg,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQACAAE/");
        final FreenetURI insertUriSSKPlain = new FreenetURI("SSK@ZTeIa1g4T3OYCdUFfHrFSlRnt5coeFFDCIZxWSb7abs,ZP4aASnyZax8nYOvCOlUebegsmbGQIXfVzw7iyOsXEc,AQECAAE/");
        assertEquals(requestUriSSKPlain, insertUriSSKPlain.deriveRequestURIFromInsertURI());
        
        try {
            requestUriSSKPlain.deriveRequestURIFromInsertURI();
            fail("requestUriSSKPlain.deriveRequestURIFromInsertURI() should fail because it IS a request URI already!");
        } catch(MalformedURLException e) {
            // Success
        }
	}

	@Test
	public void brokenUskLinkResultsInMalformedUrlException() {
		try {
			new FreenetURI("USK@/broken/0");
			fail("USK@/broken/0 is not a valid USK.");
		} catch (MalformedURLException e) {
			// Works.
		}
	}

	@Test
	public void brokenSskLinkResultsInMalformedUrlException() {
		try {
			new FreenetURI("SSK@/broken-0");
			fail("SSK@/broken-0 is not a valid SSK.");
		} catch (MalformedURLException e) {
			// Works.
		}
	}

	@Test
	public void sskCanBeCreatedWithoutRoutingKey() throws MalformedURLException {
		new FreenetURI("SSK@");
	}

}
