package freenet.pluginmanager;

import java.util.Random;

import freenet.client.FetchResult;
import freenet.keys.FreenetURI;

public class TestPlugin implements FredPlugin {
	private volatile boolean goon = true;
	Random rnd = new Random();
	PluginRespirator pr;
	/*
	public boolean handles(int thing) {
		return ((thing == FredPlugin.handleFProxy) ||
				(thing == FredPlugin.handleFProxy) ||
				(thing == FredPlugin.handleFProxy));
	}
	*/
	@Override
	public void terminate() {
		goon = false;
	}
	
	public String handleHTTPGet(String path) {
		try {
			String key = getRandomKey();//"freenet:CHK@j-v1zc0cuN3wlaCpxlKd6vT6c1jAnT9KiscVjfzLu54,q9FIlJSh8M1I1ymRBz~A0fsIcGkvUYZahZb5j7uepLA,AAEA--8";
			FetchResult fr = pr.getHLSimpleClient().fetch(new FreenetURI(key));
			
			return "This is the echo of the past.... ["+ path + "]\n" + 
			"This is from within the plugin. I've fetched some data for you.\n"+
			"The key is: " + key + ", and the data inside:\n"+
			"-----------------------------------------------------------\n"+
			new String(fr.asByteArray(), "UTF-8").trim() + '\n' +
			"-----------------------------------------------------------\n"+
			"Length: " + fr.size() + ", Mime-Type: " + fr.getMimeType();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			return e.toString();// e.printStackTrace();
		}
	}

	@Override
	public void runPlugin(PluginRespirator pr) {
		this.pr = pr;
		//pr.registerToadlet(this);
		//int i = (int)System.currentTimeMillis()%1000;
		while(goon) {
			/*System.err.println("This is a threaded test-plugin (" + 
					i + "). " +
					"Time is now: " + (new Date()));*/
			try {
				Thread.sleep(60000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
		}
	}
	
	private String getRandomKey() {
		String[] strs = {"freenet:CHK@22Lk6FBe3OxPBVMlrjFkH-4cgySwcghOz7hDQPzXKp4,wxOg3Ob8mjI5eg4WNvmDA~1CsW9MWAqwOhXXzLdrN-A,AAEA--8",
				"freenet:CHK@ZFpjcNHONAxLPDadG~v6QcSwM775-5Iw2k~QBhb63Ts,QPrrwXciPnyAEbsK5EBW-aYOGhlTaa~3YBhR-nIcx8A,AAEA--8",
				"freenet:CHK@OPpTiKOEwZbl73o7Tb~QQJaP4JEgLT8o3mP5jyJujvk,9~8TDGJAW8LfIDhC7xWrey3tEjFpdOpBa6OituIVJpA,AAEA--8",
				"freenet:CHK@sCb-Z1ATTkXvoZLBI9k3ciLz~6rqb9Io3AQL2~MliAM,a60Eoe90z5fcViuIXLecUBHFUeFf6Ge69sSdlzIHrEg,AAEA--8",
				"freenet:CHK@dcpPsRY0BG4OViN8wsXvWZTnP2ZBemz2BwENPBZC9TU,goiIQkp8OWaTc3wqr-A0Rf-aCwsEeOQPaVdjCxVpaRI,AAEA--8",
				"freenet:CHK@6OAQs98FSUo6rFcJtRMF92SFfqBXK4Yt01Yshcwp3c8,wnLfm5swG7xDbKAuSrnL8GEFz5~-4~7a2P5K8nnBV9Q,AAEA--8",
				"freenet:CHK@ePPkqAWESJVQzGO3wr6nPee8SzOuexpXYtHIWv4onTg,JlwdILVoS6OtXZqlRShtnuogFgTRO0bnXDVxUj3BusA,AAEA--8",
				"freenet:CHK@MWC8oxXg1BYZ0AJFXKOkuYrYiK7qn1PtBlBjc14Jw7o,fKxCbs0u8fjT1X-8oE3dKnQaVpZ07CpiX-gBxkwO7xc,AAEA--8",
				"freenet:CHK@~E5LGhRcvCep6pYb-odjnyS3nA0vS7Xe5GIQmmQAzMA,U3o9V86hkUMqbtgXSR9PJAQpsOodM25qDHg2KHMEJZI,AAEA--8",
				"freenet:CHK@qHaTIegEd3bWsHBB~SqvO-y3wFBU5EPEjEJBj4MJfzg,J8LcoIXB7fwXHrhPmxT6Ec-rfbw0SKTBaI84BH-pv80,AAEA--8",
				"freenet:CHK@SBBZXAsF1uSQc~cp-MC35IZTpd78hDhOQq7YTbwHgAE,c8jjxzd6f8aPDHbTy2JQRWwhEiDUNCwxO8mtZwAmq-s,AAEA--8",
				"freenet:CHK@BB~ePfxucL0NzayJ2jvtivpRXcNooyepNdhE~dvN6ec,nMNZuv0CwiObgvSyWPmTv29GPj~JuiRTl~dMuuOTOik,AAEA--8",
				"freenet:CHK@hDKNl~vitO25RmgQgbGx-Iytu4W14mHSeEIn9YyD2pg,-EJc8KXGzNtdrvKF0h90geENZgBDux~h9Gkc1fyFMUg,AAEA--8",
				"freenet:CHK@p3Xa12sfgQg3LS~VU9SsVgj4kBh58GpIqrkgRpacnFM,lyCvmN88mtvX~L9BqTQV51Ontj39X8ms5soRA~K3LU8,AAEA--8",
				"freenet:CHK@d9TEpYgV77l2zmro8igbU44F~5kUYmo-9Xoa~UFHrgw,yu-zz2qp5W2BWptDmv5Dw9~dMP3wH3n-U4qrKdMnlAQ,AAEA--8",
				"freenet:CHK@T7ytkBob45SqxK7xWZxmwAUJCdwvA1K7EPcZUAcRirc,acnBQ4qO4T2T~lA5h6QytR3UaOuzWz03tYK0~epHzHI,AAEA--8",
				"freenet:CHK@XAvuSwiW4IdyQ-4~CWOVCXGUQPsdMjP9ofLeRpWeIBE,YefUEommdgaTrVo9dxQpc-UiraiYDCnpKH65Smk6jas,AAEA--8",
				"freenet:CHK@3wdFmakCldQH0TQYQH-D4gQH8iiNT7XZM~2MVVosass,N-BZoCgtLWSZYDJ-ib44-dl2ye3m1zltK1c4yITz8bo,AAEA--8",
				"freenet:CHK@VueT2wZS~72S~QxBAXNPJCVy8GkqxMUGe78aAmeOeC4,3MZ7jVw~wNUaS~pv9w1vVYN79UkK9SUEUCaffSua07s,AAEA--8",
				"freenet:CHK@IMTdF9b9CDXy7ZvWMGxTp02llxati8iRGjtletbyqLU,2Ca6lUsRsQa~lhMesSYVPcD5SrS06anutMcqLZvcjLI,AAEA--8",
				"freenet:CHK@5tig2I52NoMH6IPAuDcE7lowJ1vqBBTowtPzxaPImp0,oXWcEqx0enGaSTniRA6ZWIEtqmm2wJb8O87VZ7kNV7w,AAEA--8",
				"freenet:CHK@1oxIcrwDCqI7hGFCd3DX4dWVsK7xVPZcyIBEHZybsXc,GeL01biXlxUnkacw4cXRsajbNrUc43h2rm390q8mMt0,AAEA--8",
				"freenet:CHK@bwieoklnnSaWHTUl3wZu9w2FCJy5uH7Dmi~XRKivGXA,ILg2cordIWtMncA2glyj9iVf6YZDYscRkO3P8sRBXDk,AAEA--8",
				"freenet:CHK@xXKRVMJpcYEa0Md2W7Rz~hsUpL80-bSx9Kc04fLeIKc,A3rmIcwQ8n33nd5xJTTrrKIOMQ3g8C2C8vjJtneYMAk,AAEA--8",
				"freenet:CHK@s8aihl-4JN9a6GF9DYUmCTN2~3PpSj0-ktQA49jYC1U,iPgZU7lVKT-gbs5ioNX9swRjveGjo0ShyOoBn8wUQ2U,AAEA--8",
				"freenet:CHK@7gZlzbJyFtHndt213T33aYySsuSyP0rxHEmI~q8yjVM,37QtrM6FXFkMVEMM4JF6xXV4lDbba~cigWJZi5-aDVk,AAEA--8",
				"freenet:CHK@MclS9N1182pJszp4LfmrLl--HX9vxTgbz9ZY44ju1FY,rUh2VghoLFpRl2fdv8hQB5uk6mEb3phg2IufhmknD~w,AAEA--8",
				"freenet:CHK@FFIUGUHHX9otAyAWjTWDbhl7IUKLuYxVPJKUARmLhjQ,hAnfNPCnDClMjbwgUKgOSNQl8o6vO7TJ5xUpsHKWMj0,AAEA--8",
				"freenet:CHK@dTqjUyWM2KY0qVcF8d6oVNqA9DfEEheg9XPTA-DlL4k,7zfbWAcvvNqwNwPyj32mraJEmJsvL6h0jPT2Ly5knO0,AAEA--8",
				"freenet:CHK@VrHAQguyuR6MyJoZ7vxb-FIuxlab3T7EuBE3OoMIVkc,S2VpJiI2wybbNXGHfhOaGz8sK75EfABlLh54RS22HI8,AAEA--8",
				"freenet:CHK@HKjIaWYvjDnWLWcf31qkwTU7g5I5M7XxxZED9XTpLB8,TnCRaYlmIhRdkIQEFTQFmpqmCci8igZ7QxrUVtbH~rU,AAEA--8",
				"freenet:CHK@-4OVhcd0Z8fpv0a~xDFL2RSO3pcyHFp1xdDiiL-0gAk,gDfK9Rq6TVEdB0C2nOF~gI7-Ha-ihMnEytSfdc3yONQ,AAEA--8",
				"freenet:CHK@MH4kDRNM91llmNWvet9rhQvTvNGyNItV0GpCLrH8nB0,Ujtgu4e3KRAtgrLTOTU8qGNvBOJQKOy8i5N835rsYdk,AAEA--8",
				"freenet:CHK@aDDabThMhxD51CJfH1zVJYGBxagnfOWbazGGCo-QiLI,BBEHrcSWL3voIJ~Y7MJQ1d7AAo4F-kp0CwUWkN3vFyE,AAEA--8",
				"freenet:CHK@YK48GQ4USNAn2XkICsEf2UiL7h3SA3PLbPCJeZEYdyg,34IBcb0gtftGYEx2rxMpqXE6PnMTTfZhWW9CEy3IwSU,AAEA--8",
				"freenet:CHK@nvxUqTKVU30vw-J3MVGHKmuJygKH~MlCEmfA~gFzuDM,TYAhcs2zH3iz6MKFO7rEevdieAqAi3GQmz5SBKdRYls,AAEA--8",
				"freenet:CHK@zFidd1PPfnInB2g6vFVXOYYLn5Z2Cb~V7eBbJDkQDc8,qa5vmLnzP~wXYHnLYVrbIqpXtM2SxJe4PyroUCFCKHo,AAEA--8",
				"freenet:CHK@wXSOHVNFfTkBeunCKH8zt8iJ6Ew0AzFbjKN6YYWPOfw,~AWwaK7-Nc0qwSmu~9GNMljr6LO8ZcuhLS5lJz7bw~U,AAEA--8",
				"freenet:CHK@gAS-BcHvDin-RTXP8CGIhMjl~bd3oWYsE0rXTmH6cmM,8UVfpn5nWN6knaxnbArtbBC8dZZjpJydglyu0mH2MQM,AAEA--8",
				"freenet:CHK@qm6NeyMAAyrK-rjludc~PU57wf8WwccW6UxdoBjW2aw,hZuS-nu6KTZcDmVUrhRjIC-D3kIpBn5za8e08p3eBOU,AAEA--8",
				"freenet:CHK@BADSo4J4DExwbFDtRqula8pg6BcBvoYO-ofG0HXxK7s,PodpsdvupZXIKBNqQvEMyR4kBENZYoX60SEOb~gwmHo,AAEA--8",
				"freenet:CHK@CMhnDAP155BeHBnvcdy0e~Zw30TSA7QFF17k0BNcghQ,qRFzWscI4FyEItjLZCAR6YeOsUAxb-zwHzO~D~7A2y8,AAEA--8",
				"freenet:CHK@85rCG8gpBLyCs9rANwe~C25qE7DuHIrw6m6k0mFawxM,bJk4gnPRVbXFhWuKUFQbNBKBYhQ-fjr97quD0--1Cu0,AAEA--8",
				"freenet:CHK@0WM8xGmUt7EN9nvf70YfiuqyQNiUW205shC4qrn~Ns0,X1Ik3nQFAGgjdmDZ1nOUU1GXiegf~G3Tz87P6R1qYg0,AAEA--8",
				"freenet:CHK@faUvYOLxYaALzqNWgCL2QLRhjqtTd-uv4uXH788DwMQ,qa-hBaE7KW3fpsQ5WjVYY~yXr0hawdbiVnrAbwbIkLA,AAEA--8",
				"freenet:CHK@oBPbYfSrA3Rx7gHQgKwPdqdszaj4ONCTzIDBXH3svgA,88C~2Qh3KsaMKP2Mpk4-O9YMzcJzecw0q5pggfE2M08,AAEA--8",
				"freenet:CHK@~Zt~6FIo-A5rzzS0tj9dzphfkvMpmd3Bk0f~kgguso8,EzIecOo7y6ylQjpB362EPthTiU2BZmrBcIsEy5B-1rU,AAEA--8",
				"freenet:CHK@5dnfR7qQRvdwy~jYSntIUYrULz2dvTIViChTa-Ga1bA,dGkeHwJwKMk~raaTPihVuXnHz6oxSdZOvlMnK35p4YI,AAEA--8",
				"freenet:CHK@luPA1WEgPIsvIN~x5mQvLGz5VJSB4RxZ~9NirffJRsE,onY58d-Mutx7486NLFVsnCqkM9vi1qdUDJSBa5R8FDU,AAEA--8",
				"freenet:CHK@TBe4MGTY9Lu6wTNdSPNx5OMAFbfxXfIkOg2pR-Vfe30,dJ9gGlMpv0bws7Ke1k-i2NM4b0S4fwD~ITturyNk744,AAEA--8",
				"freenet:CHK@F1helg8ZSNn4CdukZbtfMVefuv52USzvF3hM8mws3Ac,EimUXQHhPkv2fQkxKMuPX9QmTmJS9nrd8ArOZjxVB5E,AAEA--8",
				"freenet:CHK@LmaWgF2qdQpLsYbNGa16TWdZLexawx1b9ShmXCZIj8Y,ixYZWPQt3yyeJJRkWxVZi43y4hQ5HWvfqAEmOLkCaXQ,AAEA--8",
				"freenet:CHK@bIr5JYe5r8zkN~uDKYGHevnTy9zA4xFbGnoGk-GhKRA,iEphrMMrQg6g5WErFh~6pl0eaFadjiUnuCQgz3~NmM4,AAEA--8",
				"freenet:CHK@bGkt2Ts2GZvHhaJK-ZNLgREXTyYiJsXmxEp69gl-6Pw,xdJlV03sukfH-k2sJXvHJBe5OZk3kV5otziFck6taFE,AAEA--8",
				"freenet:CHK@Zi6UP52jpjc8VY~GvKIGTXQ~gHtLVckbgwv2s9sOZAE,fEihjP3NhwTcI0oxvMP0ltjUiGo-ajramwZkp2sMTjQ,AAEA--8",
				"freenet:CHK@aaY0424jBE--ihUHUIjnwIYcc8XsHCjYo8lZxlsYLw0,Zrbo~u7CdkQwwtuDl5WYn~RTM1x98w2MYUR45tBCe0g,AAEA--8",
				"freenet:CHK@Xnhr7YNdBoULZcx5txklkgzI1zreUGInML1VyaNuVwo,tXwbaRw-GBpR~0qfKXLxVRVccZmPQhiq1iu32uIjkJU,AAEA--8",
				"freenet:CHK@hIhlz3TKPePv3gWJ~rZZ6OB8vvgMVVfT6aPgSR1FnMA,4cotECTTOWwTeCzwYBykCm9LPV1c67l39YyrWe20FoA,AAEA--8",
				"freenet:CHK@3v00FlnsU~zGLwkk5h15EUjA4eDyedGQZc9SaK4b4r8,24jIhn37UrvUBimYfXoDEkaL4zIMTDQISN8TM3HJihk,AAEA--8",
				"freenet:CHK@iZejNyB6rzx6pBMyEgmb~bVY-rKLFRmcqbyU5nnJsOU,4SzvJPq80vGKCxoGs~YjfufJVEWOtSnK2~6oDX1~JYc,AAEA--8",
				"freenet:CHK@722nP2beISRaezmlf77tF2lPlTDFWt~f6LhWmeV3Qhg,tCXclyWJxo1Zbk5S7KaxBMpbQO4eKB~sq4cgLXY-eT4,AAEA--8",
				"freenet:CHK@3eQXbBxgp9XMFJl6RLcrnHJVpBtA93pyYfyz0~RmNTs,JTvyP5PbXhJA99HEGE0I1pjKoGue6vqUtToNWbab~6g,AAEA--8",
				"freenet:CHK@UnOcTSATPLJpMDI-GItNf~xW9AdEtnLYrV1Pv-TH0E0,IkS~sHRntHdFSNjsZCeJVnK-NonDl~MRsKUCQEOtu24,AAEA--8",
				"freenet:CHK@SZ37shG9ItJvOZQUvo9j~mvxv40mtsLrMc8MdM2JdnE,52ZfbfXwiGyvcO6sY~qhPWYq882SzGuOd7OgNSt7oLo,AAEA--8",
				"freenet:CHK@4mjEB-dHqxQg893scFwrU0~HefqUVznlMwKyRpdzzQo,FrgZGdkPy8zjQvJ8Hl2nG0JilRXGAucTBVKzZargw4Y,AAEA--8",
				"freenet:CHK@2tKNQArJRTMrQsRXZiEAYcMn2Rxe22IIWE8diNnHmoM,VsJNZXGVVX-WkgljzJSo8GYWji-z-1BZfr5cV2pygE0,AAEA--8",
				"freenet:CHK@is0FXAzhvLdv4g0~HLEXsJFSIp1v319hnMnxnhKxqLc,xiBGU9O-aoJaK7G-wcGxNMbT~c3f1RhczvmmZ8w0DSg,AAEA--8",
				"freenet:CHK@spwqSrO76h37NRtxZWQChyQHMdxexaHfyW4Ek0armbM,9oYMnoCYI-dDW3jxM4O2QJZXgiNIlMTRecJAjyQCpoA,AAEA--8",
				"freenet:CHK@Drv5UiWTFZRsMmW1XsmORmIYmFua7kQ3HC8Gpf~Z7lw,qPvQljP70NMKSoUErx-WZGFhS1-NphOMDX2PbJx-0Z4,AAEA--8",
				"freenet:CHK@ny~Z9cswPh-QAyXbBD~kVePYwFsAXxXHMojtSynm-uY,u-CTGxEFSKiI0KLc2tCYqrOzKZn-mTAjUyOND6nK~1g,AAEA--8",
				"freenet:CHK@JayfOnhoh7Ca7V5aEXBow8pjOtbl1Jrww-ZaQTv2OFM,fO3nsGwjG6TyhtodUcMSrFLod8QkNxonrcaE1qnDGCg,AAEA--8",
				"freenet:CHK@Zd7v8UX-Epu63EajrXYq-LbnQq9hpOC5s9QTTn3SD5Y,aTa~zsekEdsQzg3hkEtqFV~7r-0bVSM-pCnKmzu8-rA,AAEA--8",
				"freenet:CHK@n7sanppniixJU~Z-0dydiI~Yv7AhSUHzQma-cdiXYHQ,~g7p9ab8RoYqQQ9L-GwMx9p1q7uVMkl2T1Q7D-LND3o,AAEA--8",
				"freenet:CHK@PKMXz2s-UJNNbptKG0DxEJ9gif7A8d9WbBDwakIW61w,lXGbvq4vIegspY9LNmxdUKm~WybKSZxvxr8BaH3YG08,AAEA--8",
				"freenet:CHK@0Ic8A20PWBpXguk91k9Nd~3LrgW9t7QeMli3xNXiUdI,UEUe32p7zhNzMIn4JwxAjytf-~rUKgcfnQdXREkuTY4,AAEA--8",
				"freenet:CHK@JMvVsAIUMInRyBhO5Ed9dK9EwhdKazWRZHUU6ArhYmg,sssqrFIIt92q2uV1Zjvy4Z2vR0DM9q1KS7VL8JpvdSs,AAEA--8",
				"freenet:CHK@IkENo0XDD-Q-hviv6sZxPKwzbqf60uumVTiZJyv78PE,3GhtiGj85scotmUI4cubABS5ej~g1crGIRF7-vgWJks,AAEA--8",
				"freenet:CHK@2QGeu-1d-PXK0PSIiv~jUY3q2DNn55yJz7tMHRFTxN0,oGQEpZHQBZrp6m77JgXhD-lMd6Bi~fDK3vdfTrUlQGY,AAEA--8",
				"freenet:CHK@NbfYmO12vIzV2Ds7CPzJY7laoweeelyrdoZj2LtYCzo,eXU6jnMIz2bvRVgdlWFpiT80i04eWKTSxPJfzLAKQRY,AAEA--8",
				"freenet:CHK@t7rdcOQimxvX~usT64rIoXnu4gZKZ182DHIWlRV5n3w,~ouf6zKvL9-w7o10y8ulS40vD7CXsqsyTZQpuDGCTwY,AAEA--8",
				"freenet:CHK@9SEZVTS1gJ3~H6mAtpVbe-F2RaihUgZZSMj1jNLHK5k,IZGKoASFC7aw2pUTMqzhCuFE4Pj8bOJqlG3APCXPiBo,AAEA--8",
				"freenet:CHK@mqdcHwIG6FYjWgOlAkPnOn7k7wZPO0BwVVbtyyEjB74,wUp3cXP0kcwYa0jh9lzF6KYA5BWVPgCD2QO4ovetTcY,AAEA--8",
				"freenet:CHK@zu9IP0PY3yWFhhtbqk5MMUzrfvis3mZ2lYEyiB9bZBc,isN286~-3GMCOT7u3ydfENHpxJwpueabYVLvhewLqnQ,AAEA--8",
				"freenet:CHK@4Pd~R2UZjVY3SA~B0FbCHPz4apKxsCAHVPuEyl5YGvM,dYefpkUbgpZg1jCXddmFvE3BVk2en17zYjF735KiGJs,AAEA--8",
				"freenet:CHK@0EkRUDOKense1U7tesZJUtmh5sn5DLx6wDXhWNdWyuk,h8o-3GaSKFjkgBVgBFRTJIQQoFYkmfXBduXASFoXWW0,AAEA--8",
				"freenet:CHK@IUahAbz57yJo0qoboW6aOpRVG9ZOJOB~1UqOKc5LcT8,P9Ox2SadqD68AcmcplD4sczVZGAXlm2OmaMGw4LshD0,AAEA--8",
				"freenet:CHK@ofwNaV6jyV55tzx~ITsNtZ~WrVRaFG3obDx8TYDhISQ,uODtN3okiXN~r2xo-FGw78JM8E1ZAoz9VgpLRFQrqVM,AAEA--8",
				"freenet:CHK@EpE~qowzjOGj7PUbBDXiwjzoYsvwRScEGSKHcayWu-U,clthFYamf1PNrVODld~ncGsGO2INasyRPgZRnbkB~xs,AAEA--8",
				"freenet:CHK@AxwocOdzkWNPlje7ZCOmgjxXVesqAY-cy5c~mmfrEcY,pCEBBdxrxVuKh3~ZfNOnwqvDcVmRH2Amg-uVK~eE13E,AAEA--8",
				"freenet:CHK@cSeB79VYXKUcZqvUOrbvnal-3xQCmWzXGbfTo5vT8Y0,WCCKYWPoVL1OU2XIVwxmkX1Xl5co8XF~z9Z2CvL6Vww,AAEA--8",
				"freenet:CHK@Xb5adJ2HdJ1naklGdGm7qv~kfxtdqW7PRm6Gy9LofN8,Fy6VobihpZa4OtympTDln81VdZmnzKRPjwtj9ODgXls,AAEA--8",
				"freenet:CHK@3w6hYXe9v7LtwsjDnnwpeGx~MYSPmJ6xAcUdx8EgaeI,vYPwsvgfGSYFKBobdpokN8tgGSCbhF-lzSvKZtiRqT4,AAEA--8",
				"freenet:CHK@nA7Xr5OrNPg7onRMUsdB2iBeaZ3v0Bg8~F6VbqmCGvU,isZBjeqn~w2-K8ii1W2~KTO6kmfYD8vKj1n4SIbj1KM,AAEA--8",
				"freenet:CHK@MGHfXlEsz22DNn4u4RV4a2MQSeMB4SwicZzeqkIt38k,6wr2mczL3iKJbirah7M1rbh~9K~kUWb7H1KaqW2rP2Q,AAEA--8",
				"freenet:CHK@tjiCMAn2k1b1sh3BGrMrHaIpZGmgp5Z5B404eO8mhXY,UQ1ATP-ad4yDY~crT2CQxE9WPMtVFv~zrrGH7~8Q3JY,AAEA--8",
				"freenet:CHK@o4uf-acnRkOjQzOEvn0JOWmyxIx~LjfKkoj6Sxa3xSI,xE5o9DA-VcnKXJqN-q-szVxcQ0sOiPzmBaEIK72~Ulk,AAEA--8",
				"freenet:CHK@MIt51tETNvPoebvqnz9RrHxeIJvow-zpZ0gOTXEyb7M,1Hxx2xbrhrOZc5cwhvCkO6v5ze3MwwA2uUnicEGv8n4,AAEA--8",
				"freenet:CHK@NDD26EHpZJoEWrUX1irVVEDDHV4RG8vWby4HRIDorEQ,EwHTK7Q8EPmrzSjvaaGQaCuGvQfsvXy7EzJzB6xYq80,AAEA--8",
				"freenet:CHK@K5ZhE4VY5qIjBG9L3WciC~cFbf976rfZGxNxKBEG2mM,rly3fkg5LEby2T2albPgAVm8z8Cb2-pPJ0HdRH3YHx0,AAEA--8",
				"freenet:CHK@aH73gnCP5BHrBllzjkMidAL9omoZhArbLx~E8ohfhXY,tFiT49y7VJycJggw9oITCP5LxPza~WnrmM3KY02V~3g,AAEA--8",
				"freenet:CHK@EWWH3-KJevOv5bFRZunpJWZmKCY5UmtStpsRL~68U1A,opipVzjgGF4jKm~f9hTRY1fjrGhDfl2vQYs5IdabixE,AAEA--8"};

		return strs[rnd.nextInt(strs.length)];
	}

}
