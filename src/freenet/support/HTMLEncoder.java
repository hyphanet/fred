/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

import java.util.HashMap;
import java.util.Iterator;

/**
 * Originally from com.websiteasp.ox pasckage.
 * 
 * @author avian (Yves Lempereur)
 * @author Unique Person@w3nO30p4p9L81xKTXbCaQBOvUww (via Frost)
 */
public class HTMLEncoder {
	public final static CharTable charTable;

	public static String encode(String s) {
		int n = s.length();
		StringBuffer sb = new StringBuffer(n);
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if(Character.isLetterOrDigit(c)){ //only special characters need checking
				sb.append(c);
			} else if(charTable.containsKey(c)){
                sb.append('&');
                sb.append(charTable.get(c));
                sb.append(';');
			} else{
				sb.append(c);
		}
		}
		return sb.toString();
	}
	
	static {
		HashMap temp = new HashMap();
		temp.put(new Character((char)0), "#0");
		temp.put(new Character((char)34), "quot");
		temp.put(new Character((char)38), "amp");
		temp.put(new Character((char)39), "#39");
		temp.put(new Character((char)60), "lt");
		temp.put(new Character((char)62), "gt");
		temp.put(new Character((char)160), "nbsp");
		temp.put(new Character((char)161), "iexcl");
		temp.put(new Character((char)162), "cent");
		temp.put(new Character((char)163), "pound");
		temp.put(new Character((char)164), "curren");
		temp.put(new Character((char)165), "yen");
		temp.put(new Character((char)166), "brvbar");
		temp.put(new Character((char)167), "sect");
		temp.put(new Character((char)168), "uml");
		temp.put(new Character((char)169), "copy");
		temp.put(new Character((char)170), "ordf");
		temp.put(new Character((char)171), "laquo");
		temp.put(new Character((char)172), "not");
		temp.put(new Character((char)173), "shy");
		temp.put(new Character((char)174), "reg");
		temp.put(new Character((char)175), "macr");
		temp.put(new Character((char)176), "deg");
		temp.put(new Character((char)177), "plusmn");
		temp.put(new Character((char)178), "sup2");
		temp.put(new Character((char)179), "sup3");
		temp.put(new Character((char)180), "acute");
		temp.put(new Character((char)181), "micro");
		temp.put(new Character((char)182), "para");
		temp.put(new Character((char)183), "middot");
		temp.put(new Character((char)184), "cedil");
		temp.put(new Character((char)185), "sup1");
		temp.put(new Character((char)186), "ordm");
		temp.put(new Character((char)187), "raquo");
		temp.put(new Character((char)188), "frac14");
		temp.put(new Character((char)189), "frac12");
		temp.put(new Character((char)190), "frac34");
		temp.put(new Character((char)191), "iquest");
		temp.put(new Character((char)192), "Agrave");
		temp.put(new Character((char)193), "Aacute");
		temp.put(new Character((char)194), "Acirc");
		temp.put(new Character((char)195), "Atilde");
		temp.put(new Character((char)196), "Auml");
		temp.put(new Character((char)197), "Aring");
		temp.put(new Character((char)198), "AElig");
		temp.put(new Character((char)199), "Ccedil");
		temp.put(new Character((char)200), "Egrave");
		temp.put(new Character((char)201), "Eacute");
		temp.put(new Character((char)202), "Ecirc");
		temp.put(new Character((char)203), "Euml");
		temp.put(new Character((char)204), "Igrave");
		temp.put(new Character((char)205), "Iacute");
		temp.put(new Character((char)206), "Icirc");
		temp.put(new Character((char)207), "Iuml");
		temp.put(new Character((char)208), "ETH");
		temp.put(new Character((char)209), "Ntilde");
		temp.put(new Character((char)210), "Ograve");
		temp.put(new Character((char)211), "Oacute");
		temp.put(new Character((char)212), "Ocirc");
		temp.put(new Character((char)213), "Otilde");
		temp.put(new Character((char)214), "Ouml");
		temp.put(new Character((char)215), "times");
		temp.put(new Character((char)216), "Oslash");
		temp.put(new Character((char)217), "Ugrave");
		temp.put(new Character((char)218), "Uacute");
		temp.put(new Character((char)219), "Ucirc");
		temp.put(new Character((char)220), "Uuml");
		temp.put(new Character((char)221), "Yacute");
		temp.put(new Character((char)222), "THORN");
		temp.put(new Character((char)223), "szlig");
		temp.put(new Character((char)224), "agrave");
		temp.put(new Character((char)225), "aacute");
		temp.put(new Character((char)226), "acirc");
		temp.put(new Character((char)227), "atilde");
		temp.put(new Character((char)228), "auml");
		temp.put(new Character((char)229), "aring");
		temp.put(new Character((char)230), "aelig");
		temp.put(new Character((char)231), "ccedil");
		temp.put(new Character((char)232), "egrave");
		temp.put(new Character((char)233), "eacute");
		temp.put(new Character((char)234), "ecirc");
		temp.put(new Character((char)235), "euml");
		temp.put(new Character((char)236), "igrave");
		temp.put(new Character((char)237), "iacute");
		temp.put(new Character((char)238), "icirc");
		temp.put(new Character((char)239), "iuml");
		temp.put(new Character((char)240), "eth");
		temp.put(new Character((char)241), "ntilde");
		temp.put(new Character((char)242), "ograve");
		temp.put(new Character((char)243), "oacute");
		temp.put(new Character((char)244), "ocirc");
		temp.put(new Character((char)245), "otilde");
		temp.put(new Character((char)246), "ouml");
		temp.put(new Character((char)247), "divide");
		temp.put(new Character((char)248), "oslash");
		temp.put(new Character((char)249), "ugrave");
		temp.put(new Character((char)250), "uacute");
		temp.put(new Character((char)251), "ucirc");
		temp.put(new Character((char)252), "uuml");
		temp.put(new Character((char)253), "yacute");
		temp.put(new Character((char)254), "thorn");
		temp.put(new Character((char)255), "yuml");
		temp.put(new Character((char)260), "#260");
		temp.put(new Character((char)261), "#261");
		temp.put(new Character((char)262), "#262");
		temp.put(new Character((char)263), "#263");
		temp.put(new Character((char)280), "#280");
		temp.put(new Character((char)281), "#281");
		temp.put(new Character((char)321), "#321");
		temp.put(new Character((char)322), "#322");
		temp.put(new Character((char)323), "#323");
		temp.put(new Character((char)324), "#324");
		temp.put(new Character((char)338), "OElig");
		temp.put(new Character((char)339), "oelig");
		temp.put(new Character((char)346), "#346");
		temp.put(new Character((char)347), "#347");
		temp.put(new Character((char)352), "Scaron");
		temp.put(new Character((char)353), "scaron");
		temp.put(new Character((char)376), "Yuml");
		temp.put(new Character((char)377), "#377");
		temp.put(new Character((char)378), "#378");
		temp.put(new Character((char)379), "#379");
		temp.put(new Character((char)380), "#380");
		temp.put(new Character((char)402), "fnof");
		temp.put(new Character((char)710), "circ");
		temp.put(new Character((char)732), "tilde");
		temp.put(new Character((char)913), "Alpha");
		temp.put(new Character((char)914), "Beta");
		temp.put(new Character((char)915), "Gamma");
		temp.put(new Character((char)916), "Delta");
		temp.put(new Character((char)917), "Epsilon");
		temp.put(new Character((char)918), "Zeta");
		temp.put(new Character((char)919), "Eta");
		temp.put(new Character((char)920), "Theta");
		temp.put(new Character((char)921), "Iota");
		temp.put(new Character((char)922), "Kappa");
		temp.put(new Character((char)923), "Lambda");
		temp.put(new Character((char)924), "Mu");
		temp.put(new Character((char)925), "Nu");
		temp.put(new Character((char)926), "Xi");
		temp.put(new Character((char)927), "Omicron");
		temp.put(new Character((char)928), "Pi");
		temp.put(new Character((char)929), "Rho");
		temp.put(new Character((char)931), "Sigma");
		temp.put(new Character((char)932), "Tau");
		temp.put(new Character((char)933), "Upsilon");
		temp.put(new Character((char)934), "Phi");
		temp.put(new Character((char)935), "Chi");
		temp.put(new Character((char)936), "Psi");
		temp.put(new Character((char)937), "Omega");
		temp.put(new Character((char)945), "alpha");
		temp.put(new Character((char)946), "beta");
		temp.put(new Character((char)947), "gamma");
		temp.put(new Character((char)948), "delta");
		temp.put(new Character((char)949), "epsilon");
		temp.put(new Character((char)950), "zeta");
		temp.put(new Character((char)951), "eta");
		temp.put(new Character((char)952), "theta");
		temp.put(new Character((char)953), "iota");
		temp.put(new Character((char)954), "kappa");
		temp.put(new Character((char)955), "lambda");
		temp.put(new Character((char)956), "mu");
		temp.put(new Character((char)957), "nu");
		temp.put(new Character((char)958), "xi");
		temp.put(new Character((char)959), "omicron");
		temp.put(new Character((char)960), "pi");
		temp.put(new Character((char)961), "rho");
		temp.put(new Character((char)962), "sigmaf");
		temp.put(new Character((char)963), "sigma");
		temp.put(new Character((char)964), "tau");
		temp.put(new Character((char)965), "upsilon");
		temp.put(new Character((char)966), "phi");
		temp.put(new Character((char)967), "chi");
		temp.put(new Character((char)968), "psi");
		temp.put(new Character((char)969), "omega");
		temp.put(new Character((char)977), "thetasym");
		temp.put(new Character((char)978), "upsih");
		temp.put(new Character((char)982), "piv");
		temp.put(new Character((char)8194), "ensp");
		temp.put(new Character((char)8195), "emsp");
		temp.put(new Character((char)8201), "thinsp");
		temp.put(new Character((char)8204), "zwnj");
		temp.put(new Character((char)8205), "zwj");
		temp.put(new Character((char)8206), "lrm");
		temp.put(new Character((char)8207), "rlm");
		temp.put(new Character((char)8211), "ndash");
		temp.put(new Character((char)8212), "mdash");
		temp.put(new Character((char)8216), "lsquo");
		temp.put(new Character((char)8217), "rsquo");
		temp.put(new Character((char)8218), "sbquo");
		temp.put(new Character((char)8220), "ldquo");
		temp.put(new Character((char)8221), "rdquo");
		temp.put(new Character((char)8222), "bdquo");
		temp.put(new Character((char)8224), "dagger");
		temp.put(new Character((char)8225), "Dagger");
		temp.put(new Character((char)8226), "bull");
		temp.put(new Character((char)8230), "hellip");
		temp.put(new Character((char)8240), "permil");
		temp.put(new Character((char)8242), "prime");
		temp.put(new Character((char)8243), "Prime");
		temp.put(new Character((char)8249), "lsaquo");
		temp.put(new Character((char)8250), "rsaquo");
		temp.put(new Character((char)8254), "oline");
		temp.put(new Character((char)8260), "frasl");
		temp.put(new Character((char)8364), "euro");
		temp.put(new Character((char)8465), "image");
		temp.put(new Character((char)8472), "weierp");
		temp.put(new Character((char)8476), "real");
		temp.put(new Character((char)8482), "trade");
		temp.put(new Character((char)8501), "alefsym");
		temp.put(new Character((char)8592), "larr");
		temp.put(new Character((char)8593), "uarr");
		temp.put(new Character((char)8594), "rarr");
		temp.put(new Character((char)8595), "darr");
		temp.put(new Character((char)8596), "harr");
		temp.put(new Character((char)8629), "crarr");
		temp.put(new Character((char)8656), "lArr");
		temp.put(new Character((char)8657), "uArr");
		temp.put(new Character((char)8658), "rArr");
		temp.put(new Character((char)8659), "dArr");
		temp.put(new Character((char)8660), "hArr");
		temp.put(new Character((char)8704), "forall");
		temp.put(new Character((char)8706), "part");
		temp.put(new Character((char)8707), "exist");
		temp.put(new Character((char)8709), "empty");
		temp.put(new Character((char)8711), "nabla");
		temp.put(new Character((char)8712), "isin");
		temp.put(new Character((char)8713), "notin");
		temp.put(new Character((char)8715), "ni");
		temp.put(new Character((char)8719), "prod");
		temp.put(new Character((char)8721), "sum");
		temp.put(new Character((char)8722), "minus");
		temp.put(new Character((char)8727), "lowast");
		temp.put(new Character((char)8730), "radic");
		temp.put(new Character((char)8733), "prop");
		temp.put(new Character((char)8734), "infin");
		temp.put(new Character((char)8736), "ang");
		temp.put(new Character((char)8743), "and");
		temp.put(new Character((char)8744), "or");
		temp.put(new Character((char)8745), "cap");
		temp.put(new Character((char)8746), "cup");
		temp.put(new Character((char)8747), "int");
		temp.put(new Character((char)8756), "there4");
		temp.put(new Character((char)8764), "sim");
		temp.put(new Character((char)8773), "cong");
		temp.put(new Character((char)8776), "asymp");
		temp.put(new Character((char)8800), "ne");
		temp.put(new Character((char)8801), "equiv");
		temp.put(new Character((char)8804), "le");
		temp.put(new Character((char)8805), "ge");
		temp.put(new Character((char)8834), "sub");
		temp.put(new Character((char)8835), "sup");
		temp.put(new Character((char)8836), "nsub");
		temp.put(new Character((char)8838), "sube");
		temp.put(new Character((char)8839), "supe");
		temp.put(new Character((char)8853), "oplus");
		temp.put(new Character((char)8855), "otimes");
		temp.put(new Character((char)8869), "perp");
		temp.put(new Character((char)8901), "sdot");
		temp.put(new Character((char)8968), "lceil");
		temp.put(new Character((char)8969), "rceil");
		temp.put(new Character((char)8970), "lfloor");
		temp.put(new Character((char)8971), "rfloor");
		temp.put(new Character((char)9001), "lang");
		temp.put(new Character((char)9002), "rang");
		temp.put(new Character((char)9674), "loz");
		temp.put(new Character((char)9824), "spades");
		temp.put(new Character((char)9827), "clubs");
		temp.put(new Character((char)9829), "hearts");
		temp.put(new Character((char)9830), "diams");
		charTable = new CharTable(temp);
	}
	
	private final static class CharTable{
		private char[] chars;
		private String[] strings;
		private int modulo = 0;
		
		public CharTable(HashMap map){
			int[] keys = new int[map.size()]; 
			int keyIndex = 0;
			
			int max = 0;
			for(Iterator it = map.keySet().iterator();it.hasNext(); keyIndex++){
				int val = (int) ((Character)it.next()).charValue();
				keys[keyIndex] = val;
				if(val > max) max = val;
			}
			
			modulo = map.size();
			int[] collisionTable = new int[max+1]; //using integers instead of booleans (no cleanup)
			boolean ok=false;
			while (!ok) {
			    ++modulo; //try a higher modulo
			    ok = true;
			    for (int i = 0; ok && i < keys.length; ++i){
			    	keyIndex = keys[i]%modulo; //try this modulo
			    	if (collisionTable[keyIndex] == modulo){ //is this value already used
			    		ok = false;
			    	}
			    	else{
			    		collisionTable[keyIndex] = modulo;
					}
			    }
			}
			//System.out.println("The modulo is:" + modulo); //was The modulo is:1474
			
			chars = new char[modulo];
			strings = new String[modulo];
			Character character;
			for(Iterator it = map.keySet().iterator();it.hasNext(); keyIndex++){
				character = ((Character)it.next());
				keyIndex = character.charValue()%modulo;
				chars[keyIndex] = character.charValue();
				strings[keyIndex] = (String) map.get(character);
			}
			if (chars[0] == 0 && strings[0] != null) chars[0] = 1;
		}
		
		public boolean containsKey(char key){
			return chars[key%modulo] == key;
		}
		
		public String get(char key){
			return chars[key%modulo] == key? strings[key%modulo]:null;
		}
	}
}
