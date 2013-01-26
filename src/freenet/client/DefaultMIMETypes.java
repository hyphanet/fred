/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. 
 * Note that the mime type list is from the debian mime-types package,
 * which is public information and public domain software. */
package freenet.client;

import java.util.HashMap;
import java.util.Vector;
import java.util.regex.Pattern;

import freenet.support.Logger;

/**
 * Holds the default MIME types.
 */
public class DefaultMIMETypes {
	
	/** Default MIME type - what to set it to if we don't know any better */
	public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
	
	/** MIME types: number -> name */
	private static Vector<String> mimeTypesByNumber = new Vector<String>();
	
	/** MIME types: name -> number */
	private static HashMap<String, Short> mimeTypesByName = new HashMap<String, Short>();
	
	/** MIME types by extension. One extension maps to one MIME type, but not necessarily
	 * the other way around. */
	private static HashMap<String, Short> mimeTypesByExtension = new HashMap<String, Short>();
	
	/** Primary extension by MIME type number. */
	private static HashMap<Short, String> primaryExtensionByMimeNumber = new HashMap<Short, String>();
	
	/** All extension (String[]) by MIME type number. */
	private static HashMap<Short, String[]> allExtensionsByMimeNumber = new HashMap<Short, String[]>();
	
	/**
	 * Add a MIME type, without any extensions.
	 * @param number The number of the MIME type for compression. This *must not change*
	 * for a given type, or the metadata format will be affected.
	 * @param type The actual MIME type string. Do not include ;charset= etc; these are
	 * parameters and there is a separate mechanism for them.
	 */
	protected static synchronized void addMIMEType(short number, String type) {
		if(mimeTypesByNumber.size() > number) {
			String s = mimeTypesByNumber.get(number);
			if(s != null) throw new IllegalArgumentException("Already used: "+number);
		} else {
			mimeTypesByNumber.add(number, null);
		}
		mimeTypesByNumber.set(number, type);
		mimeTypesByName.put(type, number);
	}

	/**
	 * Add a MIME type.
	 * @param number The number of the MIME type for compression. This *must not change*
	 * for a given type, or the metadata format will be affected.
	 * @param type The actual MIME type string. Do not include ;charset= etc; these are
	 * parameters and there is a separate mechanism for them.
	 * @param extensions An array of common extensions for files of this type. Must be
	 * unique for the type.
	 */
	protected static synchronized void addMIMEType(short number, String type, String[] extensions, String outExtension) {
		addMIMEType(number, type);
		Short t = Short.valueOf(number);
		if(extensions != null) {
			for(String ext : extensions) {
				ext = ext.toLowerCase();
				Short s = mimeTypesByExtension.get(ext);
				if(s != null) {
					// No big deal
					Logger.normal(DefaultMIMETypes.class, "Extension "+ext+" assigned to "+byNumber(s.shortValue())+" in preference to "+number+ ':' +type);
				} else {
					// If only one, make it primary
					if((outExtension == null) && (extensions.length == 1))
						primaryExtensionByMimeNumber.put(t, ext);
					mimeTypesByExtension.put(ext, t);
				}
			}
			allExtensionsByMimeNumber.put(t, extensions);
		}
		if(outExtension != null)
			primaryExtensionByMimeNumber.put(t, outExtension);
				
	}

	/**
	 * Add a MIME type, with extensions separated by spaces. This is more or less
	 * the format in /etc/mime-types.
	 */
	protected static synchronized void addMIMEType(short number, String type, String extensions) {
		String[] split = extensions.split(" ");
		addMIMEType(number, type, split, split[0]);
	}

	/**
	 * Add a MIME type, with extensions separated by spaces. This is more or less
	 * the format in /etc/mime-types.
	 */
	protected static synchronized void addMIMEType(short number, String type, String extensions, String outExtension) {
		addMIMEType(number, type, extensions.split(" "), outExtension);
	}
	
	/**
	 * Get a known MIME type by number.
	 */
	public synchronized static String byNumber(short x) {
		if((x > mimeTypesByNumber.size()) || (x < 0))
			return null;
		return mimeTypesByNumber.get(x);
	}
	
	/**
	 * Get the number of a MIME type, or -1 if it is not in the table of known MIME
	 * types, in which case it will have to be sent uncompressed.
	 */
	public synchronized static short byName(String s) {
		Short x = mimeTypesByName.get(s);
		if(x != null) return x.shortValue();
		else return -1;
	}
	
	/* From toad's /etc/mime.types
	 * cat /etc/mime.types | sed "/^$/d;/#/d" | tr --squeeze '\t' ' ' | 
	 * (y=0; while read x; do echo "$x" | 
	 * sed -n "s/^\([^ ]*\)$/addMIMEType\($y, \"\1\"\);/p;s/^\([^ (),]\+\) \(.*\)$/addMIMEType\($y, \"\1\", \"\2\"\);/p;"; y=$((y+1)); done)
	 */

	// FIXME should we support aliases?
	
	static {
		addMIMEType((short)0, "application/activemessage");
		addMIMEType((short)1, "application/andrew-inset", "ez");
		addMIMEType((short)2, "application/applefile");
		addMIMEType((short)3, "application/atomicmail");
		addMIMEType((short)4, "application/batch-SMTP");
		addMIMEType((short)5, "application/beep+xml");
		addMIMEType((short)6, "application/cals-1840");
		addMIMEType((short)7, "application/commonground");
		addMIMEType((short)8, "application/cu-seeme", "csm cu");
		addMIMEType((short)9, "application/cybercash");
		addMIMEType((short)10, "application/dca-rft");
		addMIMEType((short)11, "application/dec-dx");
		addMIMEType((short)12, "application/docbook+xml");
		addMIMEType((short)13, "application/dsptype", "tsp");
		addMIMEType((short)14, "application/dvcs");
		addMIMEType((short)15, "application/edi-consent");
		addMIMEType((short)16, "application/edifact");
		addMIMEType((short)17, "application/edi-x12");
		addMIMEType((short)18, "application/eshop");
		addMIMEType((short)19, "application/font-tdpfr");
		addMIMEType((short)20, "application/futuresplash", "spl");
		addMIMEType((short)21, "application/ghostview");
		addMIMEType((short)22, "application/hta", "hta");
		addMIMEType((short)23, "application/http");
		addMIMEType((short)24, "application/hyperstudio");
		addMIMEType((short)25, "application/iges");
		addMIMEType((short)26, "application/index");
		addMIMEType((short)27, "application/index.cmd");
		addMIMEType((short)28, "application/index.obj");
		addMIMEType((short)29, "application/index.response");
		addMIMEType((short)30, "application/index.vnd");
		addMIMEType((short)31, "application/iotp");
		addMIMEType((short)32, "application/ipp");
		addMIMEType((short)33, "application/isup");
		addMIMEType((short)34, "application/mac-compactpro", "cpt");
		addMIMEType((short)35, "application/marc");
		addMIMEType((short)36, "application/mac-binhex40", "hqx");
		addMIMEType((short)37, "application/macwriteii");
		addMIMEType((short)38, "application/mathematica", "nb");
		addMIMEType((short)39, "application/mathematica-old");
		addMIMEType((short)40, "application/msaccess", "mdb");
		addMIMEType((short)41, "application/msword", "doc dot", "doc");
		addMIMEType((short)42, "application/news-message-id");
		addMIMEType((short)43, "application/news-transmission");
		addMIMEType((short)44, "application/octet-stream", "bin");
		addMIMEType((short)45, "application/ocsp-request");
		addMIMEType((short)46, "application/ocsp-response");
		addMIMEType((short)47, "application/oda", "oda");
		addMIMEType((short)48, "application/ogg", "ogg");
		addMIMEType((short)49, "application/parityfec");
		addMIMEType((short)50, "application/pics-rules", "prf");
		addMIMEType((short)51, "application/pgp-encrypted");
		addMIMEType((short)52, "application/pgp-keys", "key");
		addMIMEType((short)53, "application/pdf", "pdf");
		addMIMEType((short)54, "application/pgp-signature", "pgp");
		addMIMEType((short)55, "application/pkcs10");
		addMIMEType((short)56, "application/pkcs7-mime");
		addMIMEType((short)57, "application/pkcs7-signature");
		addMIMEType((short)58, "application/pkix-cert");
		addMIMEType((short)59, "application/pkixcmp");
		addMIMEType((short)60, "application/pkix-crl");
		addMIMEType((short)61, "application/postscript", "ps ai eps", "ps");
		addMIMEType((short)62, "application/prs.alvestrand.titrax-sheet");
		addMIMEType((short)63, "application/prs.cww");
		addMIMEType((short)64, "application/prs.nprend");
		addMIMEType((short)65, "application/qsig");
		addMIMEType((short)66, "application/rar", "rar");
		addMIMEType((short)67, "application/rdf+xml", "rdf");
		addMIMEType((short)68, "application/remote-printing");
		addMIMEType((short)69, "application/riscos");
		addMIMEType((short)70, "application/rss+xml", "rss");
		addMIMEType((short)71, "application/rtf", "rtf");
		addMIMEType((short)72, "application/sdp");
		addMIMEType((short)73, "application/set-payment");
		addMIMEType((short)74, "application/set-payment-initiation");
		addMIMEType((short)75, "application/set-registration");
		addMIMEType((short)76, "application/set-registration-initiation");
		addMIMEType((short)77, "application/sgml");
		addMIMEType((short)78, "application/sgml-open-catalog");
		addMIMEType((short)79, "application/sieve");
		addMIMEType((short)80, "application/slate");
		addMIMEType((short)81, "application/smil", "smi smil", "smil");
		addMIMEType((short)82, "application/timestamp-query");
		addMIMEType((short)83, "application/timestamp-reply");
		addMIMEType((short)84, "application/vemmi");
		addMIMEType((short)85, "application/whoispp-query");
		addMIMEType((short)86, "application/whoispp-response");
		addMIMEType((short)87, "application/wita");
		addMIMEType((short)88, "application/wordperfect5.1", "wp5");
		addMIMEType((short)89, "application/x400-bp");
		addMIMEType((short)90, "application/xhtml+xml", "xht xhtml", "xhtml");
		addMIMEType((short)91, "application/xml", "xml xsl", "xml");
		addMIMEType((short)92, "application/xml-dtd");
		addMIMEType((short)93, "application/xml-external-parsed-entity");
		addMIMEType((short)94, "application/zip", "zip");
		addMIMEType((short)95, "application/vnd.3M.Post-it-Notes");
		addMIMEType((short)96, "application/vnd.accpac.simply.aso");
		addMIMEType((short)97, "application/vnd.accpac.simply.imp");
		addMIMEType((short)98, "application/vnd.acucobol");
		addMIMEType((short)99, "application/vnd.aether.imp");
		addMIMEType((short)100, "application/vnd.anser-web-certificate-issue-initiation");
		addMIMEType((short)101, "application/vnd.anser-web-funds-transfer-initiation");
		addMIMEType((short)102, "application/vnd.audiograph");
		addMIMEType((short)103, "application/vnd.bmi");
		addMIMEType((short)104, "application/vnd.businessobjects");
		addMIMEType((short)105, "application/vnd.canon-cpdl");
		addMIMEType((short)106, "application/vnd.canon-lips");
		addMIMEType((short)107, "application/vnd.cinderella", "cdy");
		addMIMEType((short)108, "application/vnd.claymore");
		addMIMEType((short)109, "application/vnd.commerce-battelle");
		addMIMEType((short)110, "application/vnd.commonspace");
		addMIMEType((short)111, "application/vnd.comsocaller");
		addMIMEType((short)112, "application/vnd.contact.cmsg");
		addMIMEType((short)113, "application/vnd.cosmocaller");
		addMIMEType((short)114, "application/vnd.ctc-posml");
		addMIMEType((short)115, "application/vnd.cups-postscript");
		addMIMEType((short)116, "application/vnd.cups-raster");
		addMIMEType((short)117, "application/vnd.cups-raw");
		addMIMEType((short)118, "application/vnd.cybank");
		addMIMEType((short)119, "application/vnd.dna");
		addMIMEType((short)120, "application/vnd.dpgraph");
		addMIMEType((short)121, "application/vnd.dxr");
		addMIMEType((short)122, "application/vnd.ecdis-update");
		addMIMEType((short)123, "application/vnd.ecowin.chart");
		addMIMEType((short)124, "application/vnd.ecowin.filerequest");
		addMIMEType((short)125, "application/vnd.ecowin.fileupdate");
		addMIMEType((short)126, "application/vnd.ecowin.series");
		addMIMEType((short)127, "application/vnd.ecowin.seriesrequest");
		addMIMEType((short)128, "application/vnd.ecowin.seriesupdate");
		addMIMEType((short)129, "application/vnd.enliven");
		addMIMEType((short)130, "application/vnd.epson.esf");
		addMIMEType((short)131, "application/vnd.epson.msf");
		addMIMEType((short)132, "application/vnd.epson.quickanime");
		addMIMEType((short)133, "application/vnd.epson.salt");
		addMIMEType((short)134, "application/vnd.epson.ssf");
		addMIMEType((short)135, "application/vnd.ericsson.quickcall");
		addMIMEType((short)136, "application/vnd.eudora.data");
		addMIMEType((short)137, "application/vnd.fdf");
		addMIMEType((short)138, "application/vnd.ffsns");
		addMIMEType((short)139, "application/vnd.flographit");
		addMIMEType((short)140, "application/vnd.framemaker");
		addMIMEType((short)141, "application/vnd.fsc.weblaunch");
		addMIMEType((short)142, "application/vnd.fujitsu.oasys");
		addMIMEType((short)143, "application/vnd.fujitsu.oasys2");
		addMIMEType((short)144, "application/vnd.fujitsu.oasys3");
		addMIMEType((short)145, "application/vnd.fujitsu.oasysgp");
		addMIMEType((short)146, "application/vnd.fujitsu.oasysprs");
		addMIMEType((short)147, "application/vnd.fujixerox.ddd");
		addMIMEType((short)148, "application/vnd.fujixerox.docuworks");
		addMIMEType((short)149, "application/vnd.fujixerox.docuworks.binder");
		addMIMEType((short)150, "application/vnd.fut-misnet");
		addMIMEType((short)151, "application/vnd.grafeq");
		addMIMEType((short)152, "application/vnd.groove-account");
		addMIMEType((short)153, "application/vnd.groove-identity-message");
		addMIMEType((short)154, "application/vnd.groove-injector");
		addMIMEType((short)155, "application/vnd.groove-tool-message");
		addMIMEType((short)156, "application/vnd.groove-tool-template");
		addMIMEType((short)157, "application/vnd.groove-vcard");
		addMIMEType((short)158, "application/vnd.hhe.lesson-player");
		addMIMEType((short)159, "application/vnd.hp-HPGL");
		addMIMEType((short)160, "application/vnd.hp-PCL");
		addMIMEType((short)161, "application/vnd.hp-PCLXL");
		addMIMEType((short)162, "application/vnd.hp-hpid");
		addMIMEType((short)163, "application/vnd.hp-hps");
		addMIMEType((short)164, "application/vnd.httphone");
		addMIMEType((short)165, "application/vnd.hzn-3d-crossword");
		addMIMEType((short)166, "application/vnd.ibm.MiniPay");
		addMIMEType((short)167, "application/vnd.ibm.afplinedata");
		addMIMEType((short)168, "application/vnd.ibm.modcap");
		addMIMEType((short)169, "application/vnd.informix-visionary");
		addMIMEType((short)170, "application/vnd.intercon.formnet");
		addMIMEType((short)171, "application/vnd.intertrust.digibox");
		addMIMEType((short)172, "application/vnd.intertrust.nncp");
		addMIMEType((short)173, "application/vnd.intu.qbo");
		addMIMEType((short)174, "application/vnd.intu.qfx");
		addMIMEType((short)175, "application/vnd.irepository.package+xml");
		addMIMEType((short)176, "application/vnd.is-xpr");
		addMIMEType((short)177, "application/vnd.japannet-directory-service");
		addMIMEType((short)178, "application/vnd.japannet-jpnstore-wakeup");
		addMIMEType((short)179, "application/vnd.japannet-payment-wakeup");
		addMIMEType((short)180, "application/vnd.japannet-registration");
		addMIMEType((short)181, "application/vnd.japannet-registration-wakeup");
		addMIMEType((short)182, "application/vnd.japannet-setstore-wakeup");
		addMIMEType((short)183, "application/vnd.japannet-verification");
		addMIMEType((short)184, "application/vnd.japannet-verification-wakeup");
		addMIMEType((short)185, "application/vnd.koan");
		addMIMEType((short)186, "application/vnd.lotus-1-2-3");
		addMIMEType((short)187, "application/vnd.lotus-approach");
		addMIMEType((short)188, "application/vnd.lotus-freelance");
		addMIMEType((short)189, "application/vnd.lotus-notes");
		addMIMEType((short)190, "application/vnd.lotus-organizer");
		addMIMEType((short)191, "application/vnd.lotus-screencam");
		addMIMEType((short)192, "application/vnd.lotus-wordpro");
		addMIMEType((short)193, "application/vnd.mcd");
		addMIMEType((short)194, "application/vnd.mediastation.cdkey");
		addMIMEType((short)195, "application/vnd.meridian-slingshot");
		addMIMEType((short)196, "application/vnd.mif", "mif");
		addMIMEType((short)197, "application/vnd.minisoft-hp3000-save");
		addMIMEType((short)198, "application/vnd.mitsubishi.misty-guard.trustweb");
		addMIMEType((short)199, "application/vnd.mobius.daf");
		addMIMEType((short)200, "application/vnd.mobius.dis");
		addMIMEType((short)201, "application/vnd.mobius.msl");
		addMIMEType((short)202, "application/vnd.mobius.plc");
		addMIMEType((short)203, "application/vnd.mobius.txf");
		addMIMEType((short)204, "application/vnd.motorola.flexsuite");
		addMIMEType((short)205, "application/vnd.motorola.flexsuite.adsi");
		addMIMEType((short)206, "application/vnd.motorola.flexsuite.fis");
		addMIMEType((short)207, "application/vnd.motorola.flexsuite.gotap");
		addMIMEType((short)208, "application/vnd.motorola.flexsuite.kmr");
		addMIMEType((short)209, "application/vnd.motorola.flexsuite.ttc");
		addMIMEType((short)210, "application/vnd.motorola.flexsuite.wem");
		addMIMEType((short)211, "application/vnd.mozilla.xul+xml", "xul");
		addMIMEType((short)212, "application/vnd.ms-artgalry");
		addMIMEType((short)213, "application/vnd.ms-asf");
		addMIMEType((short)214, "application/vnd.ms-excel", "xls xlb xlt", "xls");
		addMIMEType((short)215, "application/vnd.ms-lrm");
		addMIMEType((short)216, "application/vnd.ms-pki.seccat", "cat");
		addMIMEType((short)217, "application/vnd.ms-pki.stl", "stl");
		addMIMEType((short)218, "application/vnd.ms-powerpoint", "ppt pps", "pps");
		addMIMEType((short)219, "application/vnd.ms-project");
		addMIMEType((short)220, "application/vnd.ms-tnef");
		addMIMEType((short)221, "application/vnd.ms-works");
		addMIMEType((short)222, "application/vnd.mseq");
		addMIMEType((short)223, "application/vnd.msign");
		addMIMEType((short)224, "application/vnd.music-niff");
		addMIMEType((short)225, "application/vnd.musician");
		addMIMEType((short)226, "application/vnd.netfpx");
		addMIMEType((short)227, "application/vnd.noblenet-directory");
		addMIMEType((short)228, "application/vnd.noblenet-sealer");
		addMIMEType((short)229, "application/vnd.noblenet-web");
		addMIMEType((short)230, "application/vnd.novadigm.EDM");
		addMIMEType((short)231, "application/vnd.novadigm.EDX");
		addMIMEType((short)232, "application/vnd.novadigm.EXT");
		addMIMEType((short)233, "application/vnd.osa.netdeploy");
		addMIMEType((short)234, "application/vnd.palm");
		addMIMEType((short)235, "application/vnd.pg.format");
		addMIMEType((short)236, "application/vnd.pg.osasli");
		addMIMEType((short)237, "application/vnd.powerbuilder6");
		addMIMEType((short)238, "application/vnd.powerbuilder6-s");
		addMIMEType((short)239, "application/vnd.powerbuilder7");
		addMIMEType((short)240, "application/vnd.powerbuilder7-s");
		addMIMEType((short)241, "application/vnd.powerbuilder75");
		addMIMEType((short)242, "application/vnd.powerbuilder75-s");
		addMIMEType((short)243, "application/vnd.previewsystems.box");
		addMIMEType((short)244, "application/vnd.publishare-delta-tree");
		addMIMEType((short)245, "application/vnd.pvi.ptid1");
		addMIMEType((short)246, "application/vnd.pwg-xhtml-print+xml");
		addMIMEType((short)247, "application/vnd.rapid");
		addMIMEType((short)248, "application/vnd.s3sms");
		addMIMEType((short)249, "application/vnd.seemail");
		addMIMEType((short)250, "application/vnd.shana.informed.formdata");
		addMIMEType((short)251, "application/vnd.shana.informed.formtemplate");
		addMIMEType((short)252, "application/vnd.shana.informed.interchange");
		addMIMEType((short)253, "application/vnd.shana.informed.package");
		addMIMEType((short)254, "application/vnd.smaf", "mmf");
		addMIMEType((short)255, "application/vnd.sss-cod");
		addMIMEType((short)256, "application/vnd.sss-dtf");
		addMIMEType((short)257, "application/vnd.sss-ntf");
		addMIMEType((short)258, "application/vnd.stardivision.calc", "sdc");
		addMIMEType((short)259, "application/vnd.stardivision.draw", "sda");
		addMIMEType((short)260, "application/vnd.stardivision.impress", "sdd sdp");
		addMIMEType((short)261, "application/vnd.stardivision.math", "smf");
		addMIMEType((short)262, "application/vnd.stardivision.writer", "sdw vor");
		addMIMEType((short)263, "application/vnd.stardivision.writer-global", "sgl");
		addMIMEType((short)264, "application/vnd.street-stream");
		addMIMEType((short)265, "application/vnd.sun.xml.calc", "sxc");
		addMIMEType((short)266, "application/vnd.sun.xml.calc.template", "stc");
		addMIMEType((short)267, "application/vnd.sun.xml.draw", "sxd");
		addMIMEType((short)268, "application/vnd.sun.xml.draw.template", "std");
		addMIMEType((short)269, "application/vnd.sun.xml.impress", "sxi");
		addMIMEType((short)270, "application/vnd.sun.xml.impress.template", "sti");
		addMIMEType((short)271, "application/vnd.sun.xml.math", "sxm");
		addMIMEType((short)272, "application/vnd.sun.xml.writer", "sxw");
		addMIMEType((short)273, "application/vnd.sun.xml.writer.global", "sxg");
		addMIMEType((short)274, "application/vnd.sun.xml.writer.template", "stw");
		addMIMEType((short)275, "application/vnd.svd");
		addMIMEType((short)276, "application/vnd.swiftview-ics");
		addMIMEType((short)277, "application/vnd.symbian.install", "sis");
		addMIMEType((short)278, "application/vnd.triscape.mxs");
		addMIMEType((short)279, "application/vnd.trueapp");
		addMIMEType((short)280, "application/vnd.truedoc");
		addMIMEType((short)281, "application/vnd.tve-trigger");
		addMIMEType((short)282, "application/vnd.ufdl");
		addMIMEType((short)283, "application/vnd.uplanet.alert");
		addMIMEType((short)284, "application/vnd.uplanet.alert-wbxml");
		addMIMEType((short)285, "application/vnd.uplanet.bearer-choice");
		addMIMEType((short)286, "application/vnd.uplanet.bearer-choice-wbxml");
		addMIMEType((short)287, "application/vnd.uplanet.cacheop");
		addMIMEType((short)288, "application/vnd.uplanet.cacheop-wbxml");
		addMIMEType((short)289, "application/vnd.uplanet.channel");
		addMIMEType((short)290, "application/vnd.uplanet.channel-wbxml");
		addMIMEType((short)291, "application/vnd.uplanet.list");
		addMIMEType((short)292, "application/vnd.uplanet.list-wbxml");
		addMIMEType((short)293, "application/vnd.uplanet.listcmd");
		addMIMEType((short)294, "application/vnd.uplanet.listcmd-wbxml");
		addMIMEType((short)295, "application/vnd.uplanet.signal");
		addMIMEType((short)296, "application/vnd.vcx");
		addMIMEType((short)297, "application/vnd.vectorworks");
		addMIMEType((short)298, "application/vnd.vidsoft.vidconference");
		addMIMEType((short)299, "application/vnd.visio", "vsd");
		addMIMEType((short)300, "application/vnd.vividence.scriptfile");
		addMIMEType((short)301, "application/vnd.wap.sic");
		addMIMEType((short)302, "application/vnd.wap.slc");
		addMIMEType((short)303, "application/vnd.wap.wbxml", "wbxml");
		addMIMEType((short)304, "application/vnd.wap.wmlc", "wmlc");
		addMIMEType((short)305, "application/vnd.wap.wmlscriptc", "wmlsc");
		addMIMEType((short)306, "application/vnd.webturbo");
		addMIMEType((short)307, "application/vnd.wrq-hp3000-labelled");
		addMIMEType((short)308, "application/vnd.wt.stf");
		addMIMEType((short)309, "application/vnd.xara");
		addMIMEType((short)310, "application/vnd.xfdl");
		addMIMEType((short)311, "application/vnd.yellowriver-custom-menu");
		addMIMEType((short)312, "application/x-123", "wk");
		addMIMEType((short)313, "application/x-apple-diskimage", "dmg");
		addMIMEType((short)314, "application/x-bcpio", "bcpio");
		addMIMEType((short)315, "application/x-bittorrent", "torrent");
		addMIMEType((short)316, "application/x-cdf", "cdf");
		addMIMEType((short)317, "application/x-cdlink", "vcd");
		addMIMEType((short)318, "application/x-chess-pgn", "pgn");
		addMIMEType((short)319, "application/x-chm", "chm");
		addMIMEType((short)320, "application/x-core");
		addMIMEType((short)321, "application/x-cpio", "cpio");
		addMIMEType((short)322, "application/x-csh", "csh");
		addMIMEType((short)323, "application/x-debian-package", "deb");
		addMIMEType((short)324, "application/x-director", "dcr dir dxr");
		addMIMEType((short)325, "application/x-doom", "wad");
		addMIMEType((short)326, "application/x-dms", "dms");
		addMIMEType((short)327, "application/x-dvi", "dvi");
		addMIMEType((short)328, "application/x-executable");
		addMIMEType((short)329, "application/x-flac", "flac");
		addMIMEType((short)330, "application/x-font", "pfa pfb gsf pcf pcf.Z", "unknown-font-type");
		addMIMEType((short)331, "application/x-futuresplash", "spl");
		addMIMEType((short)332, "application/x-gnumeric", "gnumeric");
		addMIMEType((short)333, "application/x-go-sgf", "sgf");
		addMIMEType((short)334, "application/x-graphing-calculator", "gcf");
		addMIMEType((short)335, "application/x-gtar", "gtar tgz taz", "tgz");
		addMIMEType((short)336, "application/x-hdf", "hdf");
		addMIMEType((short)337, "application/x-httpd-php", "phtml pht php", "php");
		addMIMEType((short)338, "application/x-httpd-php-source", "phps");
		addMIMEType((short)339, "application/x-httpd-php3", "php3");
		addMIMEType((short)340, "application/x-httpd-php3-preprocessed", "php3p");
		addMIMEType((short)341, "application/x-httpd-php4", "php4");
		addMIMEType((short)342, "application/x-ica", "ica");
		addMIMEType((short)343, "application/x-internet-signup", "ins isp");
		addMIMEType((short)344, "application/x-iphone", "iii");
		addMIMEType((short)345, "application/x-java-applet");
		addMIMEType((short)346, "application/x-java-archive", "jar");
		addMIMEType((short)347, "application/x-java-bean");
		addMIMEType((short)348, "application/x-java-jnlp-file", "jnlp");
		addMIMEType((short)349, "application/x-java-serialized-object", "ser");
		addMIMEType((short)350, "application/x-java-vm", "class");
		addMIMEType((short)351, "application/x-javascript", "js");
		addMIMEType((short)352, "application/x-kdelnk");
		addMIMEType((short)353, "application/x-kchart", "chrt");
		addMIMEType((short)354, "application/x-killustrator", "kil");
		addMIMEType((short)355, "application/x-kpresenter", "kpr kpt");
		addMIMEType((short)356, "application/x-koan", "skp skd skt skm");
		addMIMEType((short)357, "application/x-kspread", "ksp");
		addMIMEType((short)358, "application/x-kword", "kwd kwt", "kwd");
		addMIMEType((short)359, "application/x-latex", "latex");
		addMIMEType((short)360, "application/x-lha", "lha");
		addMIMEType((short)361, "application/x-lzh", "lzh");
		addMIMEType((short)362, "application/x-lzx", "lzx");
		addMIMEType((short)363, "application/x-maker", "frm maker frame fm fb book fbdoc");
		addMIMEType((short)364, "application/x-mif", "mif");
		addMIMEType((short)365, "application/x-ms-wmz", "wmz");
		addMIMEType((short)366, "application/x-ms-wmd", "wmd");
		addMIMEType((short)367, "application/x-msdos-program", "com exe bat dll", "exe");
		addMIMEType((short)368, "application/x-msi", "msi");
		addMIMEType((short)369, "application/x-netcdf", "nc");
		addMIMEType((short)370, "application/x-ns-proxy-autoconfig", "pac");
		addMIMEType((short)371, "application/x-nwc", "nwc");
		addMIMEType((short)372, "application/x-object", "o");
		addMIMEType((short)373, "application/x-oz-application", "oza");
		addMIMEType((short)374, "application/x-pkcs7-certreqresp", "p7r");
		addMIMEType((short)375, "application/x-pkcs7-crl", "crl");
		addMIMEType((short)376, "application/x-python-code", "pyc pyo", "unknown-pyc-pyo");
		addMIMEType((short)377, "application/x-quicktimeplayer", "qtl");
		addMIMEType((short)378, "application/x-redhat-package-manager", "rpm");
		addMIMEType((short)379, "application/x-rx");
		addMIMEType((short)380, "application/x-sh");
		addMIMEType((short)381, "application/x-shar", "shar");
		addMIMEType((short)382, "application/x-shellscript");
		addMIMEType((short)383, "application/x-shockwave-flash", "swf swfl", "swf");
		addMIMEType((short)384, "application/x-sh", "sh");
		addMIMEType((short)385, "application/x-stuffit", "sit");
		addMIMEType((short)386, "application/x-sv4cpio", "sv4cpio");
		addMIMEType((short)387, "application/x-sv4crc", "sv4crc");
		addMIMEType((short)388, "application/x-tar", "tar");
		addMIMEType((short)389, "application/x-tcl", "tcl");
		addMIMEType((short)390, "application/x-tex-gf", "gf");
		addMIMEType((short)391, "application/x-tex-pk", "pk");
		addMIMEType((short)392, "application/x-texinfo", "texinfo texi", "texi");
		addMIMEType((short)393, "application/x-trash", "~ % bak old sik");
		addMIMEType((short)394, "application/x-troff", "t tr roff");
		addMIMEType((short)395, "application/x-troff-man", "man");
		addMIMEType((short)396, "application/x-troff-me", "me");
		addMIMEType((short)397, "application/x-troff-ms", "ms");
		addMIMEType((short)398, "application/x-ustar", "ustar");
		addMIMEType((short)399, "application/x-videolan");
		addMIMEType((short)400, "application/x-wais-source", "src");
		addMIMEType((short)401, "application/x-wingz", "wz");
		addMIMEType((short)402, "application/x-x509-ca-cert", "crt");
		addMIMEType((short)403, "application/x-xcf", "xcf");
		addMIMEType((short)404, "application/x-xfig", "fig");
		addMIMEType((short)405, "audio/32kadpcm");
		addMIMEType((short)406, "audio/basic", "au snd", "au");
		addMIMEType((short)407, "audio/g.722.1");
		addMIMEType((short)408, "audio/l16");
		addMIMEType((short)409, "audio/midi", "mid midi kar", "mid");
		addMIMEType((short)410, "audio/mp4a-latm");
		addMIMEType((short)411, "audio/mpa-robust");
		addMIMEType((short)412, "audio/mpeg", "mpga mpega mp2 mp3 m4a", "mp3");
		addMIMEType((short)413, "audio/mpegurl", "m3u");
		addMIMEType((short)414, "audio/parityfec");
		addMIMEType((short)415, "audio/prs.sid", "sid");
		addMIMEType((short)416, "audio/telephone-event");
		addMIMEType((short)417, "audio/tone");
		addMIMEType((short)418, "audio/vnd.cisco.nse");
		addMIMEType((short)419, "audio/vnd.cns.anp1");
		addMIMEType((short)420, "audio/vnd.cns.inf1");
		addMIMEType((short)421, "audio/vnd.digital-winds");
		addMIMEType((short)422, "audio/vnd.everad.plj");
		addMIMEType((short)423, "audio/vnd.lucent.voice");
		addMIMEType((short)424, "audio/vnd.nortel.vbk");
		addMIMEType((short)425, "audio/vnd.nuera.ecelp4800");
		addMIMEType((short)426, "audio/vnd.nuera.ecelp7470");
		addMIMEType((short)427, "audio/vnd.nuera.ecelp9600");
		addMIMEType((short)428, "audio/vnd.octel.sbc");
		addMIMEType((short)429, "audio/vnd.qcelp");
		addMIMEType((short)430, "audio/vnd.rhetorex.32kadpcm");
		addMIMEType((short)431, "audio/vnd.vmx.cvsd");
		addMIMEType((short)432, "audio/x-aiff", "aif aiff aifc", "aiff");
		addMIMEType((short)433, "audio/x-gsm", "gsm");
		addMIMEType((short)434, "audio/x-mpegurl", "m3u");
		addMIMEType((short)435, "audio/x-ms-wma", "wma");
		addMIMEType((short)436, "audio/x-ms-wax", "wax");
		addMIMEType((short)437, "audio/x-pn-realaudio-plugin");
		addMIMEType((short)438, "audio/x-pn-realaudio", "ra rm ram", "ra");
		addMIMEType((short)439, "audio/x-realaudio", "ra");
		addMIMEType((short)440, "audio/x-scpls", "pls");
		addMIMEType((short)441, "audio/x-sd2", "sd2");
		addMIMEType((short)442, "audio/x-wav", "wav");
		addMIMEType((short)443, "chemical/x-pdb", "pdb");
		addMIMEType((short)444, "chemical/x-xyz", "xyz");
		addMIMEType((short)445, "image/cgm");
		addMIMEType((short)446, "image/g3fax");
		addMIMEType((short)447, "image/gif", "gif");
		addMIMEType((short)448, "image/ief", "ief");
		addMIMEType((short)449, "image/jpeg", "jpeg jpg jpe", "jpeg");
		addMIMEType((short)450, "image/naplps");
		addMIMEType((short)451, "image/pcx", "pcx");
		addMIMEType((short)452, "image/png", "png");
		addMIMEType((short)453, "image/prs.btif");
		addMIMEType((short)454, "image/prs.pti");
		addMIMEType((short)455, "image/svg+xml", "svg svgz", "svg");
		addMIMEType((short)456, "image/tiff", "tiff tif", "tiff");
		addMIMEType((short)457, "image/vnd.cns.inf2");
		addMIMEType((short)458, "image/vnd.djvu", "djvu djv");
		addMIMEType((short)459, "image/vnd.dwg");
		addMIMEType((short)460, "image/vnd.dxf");
		addMIMEType((short)461, "image/vnd.fastbidsheet");
		addMIMEType((short)462, "image/vnd.fpx");
		addMIMEType((short)463, "image/vnd.fst");
		addMIMEType((short)464, "image/vnd.fujixerox.edmics-mmr");
		addMIMEType((short)465, "image/vnd.fujixerox.edmics-rlc");
		addMIMEType((short)466, "image/vnd.mix");
		addMIMEType((short)467, "image/vnd.net-fpx");
		addMIMEType((short)468, "image/vnd.svf");
		addMIMEType((short)469, "image/vnd.wap.wbmp", "wbmp");
		addMIMEType((short)470, "image/vnd.xiff");
		addMIMEType((short)471, "image/x-cmu-raster", "ras");
		addMIMEType((short)472, "image/x-coreldraw", "cdr");
		addMIMEType((short)473, "image/x-coreldrawpattern", "pat");
		addMIMEType((short)474, "image/x-coreldrawtemplate", "cdt");
		addMIMEType((short)475, "image/x-corelphotopaint", "cpt");
		addMIMEType((short)476, "image/x-icon", "ico");
		addMIMEType((short)477, "image/x-jg", "art");
		addMIMEType((short)478, "image/x-jng", "jng");
		addMIMEType((short)479, "image/x-ms-bmp", "bmp");
		addMIMEType((short)480, "image/x-photoshop", "psd");
		addMIMEType((short)481, "image/x-portable-anymap", "pnm");
		addMIMEType((short)482, "image/x-portable-bitmap", "pbm");
		addMIMEType((short)483, "image/x-portable-graymap", "pgm");
		addMIMEType((short)484, "image/x-portable-pixmap", "ppm");
		addMIMEType((short)485, "image/x-rgb", "rgb");
		addMIMEType((short)486, "image/x-xbitmap", "xbm");
		addMIMEType((short)487, "image/x-xpixmap", "xpm");
		addMIMEType((short)488, "image/x-xwindowdump", "xwd");
		addMIMEType((short)489, "inode/chardevice");
		addMIMEType((short)490, "inode/blockdevice");
		addMIMEType((short)491, "inode/directory-locked");
		addMIMEType((short)492, "inode/directory");
		addMIMEType((short)493, "inode/fifo");
		addMIMEType((short)494, "inode/socket");
		addMIMEType((short)495, "message/delivery-status");
		addMIMEType((short)496, "message/disposition-notification");
		addMIMEType((short)497, "message/external-body");
		addMIMEType((short)498, "message/http");
		addMIMEType((short)499, "message/s-http");
		addMIMEType((short)500, "message/news");
		addMIMEType((short)501, "message/partial");
		addMIMEType((short)502, "message/rfc822");
		addMIMEType((short)503, "model/iges", "igs iges");
		addMIMEType((short)504, "model/mesh", "msh mesh silo");
		addMIMEType((short)505, "model/vnd.dwf");
		addMIMEType((short)506, "model/vnd.flatland.3dml");
		addMIMEType((short)507, "model/vnd.gdl");
		addMIMEType((short)508, "model/vnd.gs-gdl");
		addMIMEType((short)509, "model/vnd.gtw");
		addMIMEType((short)510, "model/vnd.mts");
		addMIMEType((short)511, "model/vnd.vtu");
		addMIMEType((short)512, "model/vrml", "wrl vrml", "vrml");
		addMIMEType((short)513, "multipart/alternative");
		addMIMEType((short)514, "multipart/appledouble");
		addMIMEType((short)515, "multipart/byteranges");
		addMIMEType((short)516, "multipart/digest");
		addMIMEType((short)517, "multipart/encrypted");
		addMIMEType((short)518, "multipart/form-data");
		addMIMEType((short)519, "multipart/header-set");
		addMIMEType((short)520, "multipart/mixed");
		addMIMEType((short)521, "multipart/parallel");
		addMIMEType((short)522, "multipart/related");
		addMIMEType((short)523, "multipart/report");
		addMIMEType((short)524, "multipart/signed");
		addMIMEType((short)525, "multipart/voice-message");
		addMIMEType((short)526, "text/calendar", "ics icz", "ics");
		addMIMEType((short)527, "text/comma-separated-values", "csv");
		addMIMEType((short)528, "text/css", "css");
		addMIMEType((short)529, "text/directory");
		addMIMEType((short)530, "text/english");
		addMIMEType((short)531, "text/enriched");
		addMIMEType((short)532, "text/h323", "323");
		addMIMEType((short)533, "text/html", "htm html shtml", "html");
		addMIMEType((short)534, "text/iuls", "uls");
		addMIMEType((short)535, "text/mathml", "mml");
		addMIMEType((short)536, "text/parityfec");
		addMIMEType((short)537, "text/plain", "asc txt text diff pot", "txt");
		addMIMEType((short)538, "text/prs.lines.tag");
		addMIMEType((short)539, "text/rfc822-headers");
		addMIMEType((short)540, "text/richtext", "rtx");
		addMIMEType((short)541, "text/rtf", "rtf");
		addMIMEType((short)542, "text/scriptlet", "sct wsc");
		addMIMEType((short)543, "text/t140");
		addMIMEType((short)544, "text/texmacs", "tm ts");
		addMIMEType((short)545, "text/tab-separated-values", "tsv");
		addMIMEType((short)546, "text/uri-list");
		addMIMEType((short)547, "text/vnd.abc");
		addMIMEType((short)548, "text/vnd.curl");
		addMIMEType((short)549, "text/vnd.DMClientScript");
		addMIMEType((short)550, "text/vnd.flatland.3dml");
		addMIMEType((short)551, "text/vnd.fly");
		addMIMEType((short)552, "text/vnd.fmi.flexstor");
		addMIMEType((short)553, "text/vnd.in3d.3dml");
		addMIMEType((short)554, "text/vnd.in3d.spot");
		addMIMEType((short)555, "text/vnd.IPTC.NewsML");
		addMIMEType((short)556, "text/vnd.IPTC.NITF");
		addMIMEType((short)557, "text/vnd.latex-z");
		addMIMEType((short)558, "text/vnd.motorola.reflex");
		addMIMEType((short)559, "text/vnd.ms-mediapackage");
		addMIMEType((short)560, "text/vnd.sun.j2me.app-descriptor", "jad");
		addMIMEType((short)561, "text/vnd.wap.si");
		addMIMEType((short)562, "text/vnd.wap.sl");
		addMIMEType((short)563, "text/vnd.wap.wml", "wml");
		addMIMEType((short)564, "text/vnd.wap.wmlscript", "wmls");
		addMIMEType((short)565, "text/x-c++hdr", "h++ hpp hxx hh", "hh");
		addMIMEType((short)566, "text/x-c++src", "c++ cpp cxx cc", "cc");
		addMIMEType((short)567, "text/x-chdr", "h");
		addMIMEType((short)568, "text/x-crontab");
		addMIMEType((short)569, "text/x-csh", "csh");
		addMIMEType((short)570, "text/x-csrc", "c");
		addMIMEType((short)571, "text/x-java", "java");
		addMIMEType((short)572, "text/x-makefile");
		addMIMEType((short)573, "text/x-moc", "moc");
		addMIMEType((short)574, "text/x-pascal", "p pas", "pas");
		addMIMEType((short)575, "text/x-pcs-gcd", "gcd");
		addMIMEType((short)576, "text/x-perl", "pl pm", "pl");
		addMIMEType((short)577, "text/x-python", "py");
		addMIMEType((short)578, "text/x-server-parsed-html", "shmtl", "shtml");
		addMIMEType((short)579, "text/x-setext", "etx");
		addMIMEType((short)580, "text/x-sh", "sh");
		addMIMEType((short)581, "text/x-tcl", "tcl tk", "tcl");
		addMIMEType((short)582, "text/x-tex", "tex ltx sty cls", "tex");
		addMIMEType((short)583, "text/x-vcalendar", "vcs");
		addMIMEType((short)584, "text/x-vcard", "vcf");
		addMIMEType((short)585, "video/dl", "dl");
		addMIMEType((short)586, "video/fli", "fli");
		addMIMEType((short)587, "video/gl", "gl");
		addMIMEType((short)588, "video/mpeg", "mpeg mpg mpe", "mpeg");
		addMIMEType((short)589, "video/mp4", "mp4");
		addMIMEType((short)590, "video/quicktime", "qt mov", "mov");
		addMIMEType((short)591, "video/mp4v-es");
		addMIMEType((short)592, "video/parityfec");
		addMIMEType((short)593, "video/pointer");
		addMIMEType((short)594, "video/vnd.fvt");
		addMIMEType((short)595, "video/vnd.motorola.video");
		addMIMEType((short)596, "video/vnd.motorola.videop");
		addMIMEType((short)597, "video/vnd.mpegurl", "mxu");
		addMIMEType((short)598, "video/vnd.mts");
		addMIMEType((short)599, "video/vnd.nokia.interleaved-multimedia");
		addMIMEType((short)600, "video/vnd.vivo");
		addMIMEType((short)601, "video/x-dv", "dif dv");
		addMIMEType((short)602, "video/x-la-asf", "lsf lsx", "lsf");
		addMIMEType((short)603, "video/x-mng", "mng");
		addMIMEType((short)604, "video/x-ms-asf", "asf asx", "asf");
		addMIMEType((short)605, "video/x-ms-wm", "wm");
		addMIMEType((short)606, "video/x-ms-wmv", "wmv");
		addMIMEType((short)607, "video/x-ms-wmx", "wmx");
		addMIMEType((short)608, "video/x-ms-wvx", "wvx");
		addMIMEType((short)609, "video/x-msvideo", "avi");
		addMIMEType((short)610, "video/x-sgi-movie", "movie");
		addMIMEType((short)611, "x-conference/x-cooltalk", "ice");
		addMIMEType((short)612, "x-world/x-vrml", "vrm vrml wrl", "vrml");
		addMIMEType((short)613, "binary/zip-compressed", "zip");
		addMIMEType((short)614, "video/ogg", "ogv");
		addMIMEType((short)615, "video/matroska", "mkv");
		addMIMEType((short)616, "video/flash", "flv");
		addMIMEType((short)617, "video/ogg-media", "ogm");
		addMIMEType((short)618, "application/x-7z-compressed", "7z");
		addMIMEType((short)619, "audio/speex", "spx");
		addMIMEType((short)620, "audio/ogg", "oga");
		addMIMEType((short)621, "audio/flac", "flac");
	}
	
	/** Guess a MIME type from a filename.
	 * @param noDefault If true, no default MIME type; return null if not recognized.
	 * Otherwise if we don't recognize the extension we return DEFAULT_MIME_TYPE. */
	public synchronized static String guessMIMEType(String arg, boolean noDefault) {
		int x = arg.lastIndexOf('.');
		if((x == -1) || (x == arg.length()-1))
			return noDefault ? null : DEFAULT_MIME_TYPE;
		String ext = arg.substring(x+1).toLowerCase();
		Short mimeIndexOb = mimeTypesByExtension.get(ext);
		if(mimeIndexOb != null) {
			return mimeTypesByNumber.get(mimeIndexOb.intValue());
		} else return noDefault ? null : DEFAULT_MIME_TYPE;
	}

	public synchronized static String getExtension(String type) {
		short typeNumber = byName(type);
		if(typeNumber < 0) return null;
		return primaryExtensionByMimeNumber.get(typeNumber);
	}
	
	public synchronized static boolean isValidExt(String expectedMimeType, String oldExt) {
		short typeNumber = byName(expectedMimeType);
		if(typeNumber < 0) return false;
		
		String[] extensions = allExtensionsByMimeNumber.get(typeNumber);
		if(extensions == null) return false;
		for(String extension: extensions)
			if(oldExt.equals(extension)) return true;
		return false;
	}
	
	private static final String TOP_LEVEL = "(?>[a-zA-Z-]+)";
	private static final String CHARS = "(?>[a-zA-Z0-9+_\\-\\.]+)";
	private static final String PARAM = "(?>;\\s*"+CHARS+"="+"(("+CHARS+")|(\".*\")))";
	private static Pattern MIME_TYPE = Pattern.compile(TOP_LEVEL+"/"+CHARS+"\\s*"+PARAM+"*");

	private static Pattern INFOCALYPSE_DIRTY_HACK = Pattern.compile("application/mercurial-bundle;[0-9]{1,6}");
	
	public static boolean isPlausibleMIMEType(String mimeType) {
		if(MIME_TYPE.matcher(mimeType).matches()) return true;
		// FIXME dirty hack for backwards compatibility with old Infocalypse repo's
		return INFOCALYPSE_DIRTY_HACK.matcher(mimeType).matches();
	}
	
	static String[] getMIMETypes() {
		return mimeTypesByNumber.toArray(new String[mimeTypesByNumber.size()]);
	}
}
