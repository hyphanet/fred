/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.test;

import junit.framework.TestCase;

/**
 * Utility class used throught test cases classes
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public final class UTFUtil extends TestCase {

	public void testFake() {
		
	}
	
	/**
	 * Contains all unicode characters except the low and high surrogates (they are no valid characters and constructing strings with them will cause
	 * the JRE to replace them with the default replacement character). Even 0x0000 is included.
	 */
	public static final char[] ALL_CHARACTERS;
	
	static {
		ALL_CHARACTERS = new char[Character.MAX_VALUE - Character.MIN_VALUE + 1];
		
		for(int i = 0; i <= (Character.MAX_VALUE - Character.MIN_VALUE); ++i) {
			int characterValue = (Character.MIN_VALUE + i);
			
			// The low and high surrogates are no valid unicode characters.
			if(characterValue >= Character.MIN_LOW_SURROGATE && characterValue <= Character.MAX_LOW_SURROGATE)
				ALL_CHARACTERS[i]  = ' ';
			else if(characterValue >= Character.MIN_HIGH_SURROGATE && characterValue <= Character.MAX_HIGH_SURROGATE)
				ALL_CHARACTERS[i]  = ' ';
			else
				ALL_CHARACTERS[i] = (char)characterValue;
		}
	}
	
	//printable ascii symbols
	public static final char PRINTABLE_ASCII[] = {
		' ','!','@','#','$','%','^','&','(',')','+','=','{','}','[',']',':',';','\\','\"','\'',
		',','<','>','.','?','~','`'};

	//stressed UTF chars values
	public static final char STRESSED_UTF[] = { 
		//ÉâûĔĭņşÊãüĕĮŇŠËäýĖįňšÌåþėİŉŢÍæÿĘıŊţÎçĀęĲŋŤÏèāĚĳŌťÐéĂěĴōŦÑêăĜĵŎŧ
		'\u00c9','\u00e2','\u00fb','\u0114','\u012d','\u0146','\u015f','\u00ca','\u00e3','\u00fc',
		'\u0115','\u012e','\u0147','\u0160','\u00cb','\u00e4','\u00fd','\u0116','\u012f','\u0148',
		'\u0161','\u00cc','\u00e5','\u00fe','\u0117','\u0130','\u0149','\u0162','\u00cd','\u00e6',
		'\u00ff','\u0118','\u0131','\u014a','\u0163','\u00ce','\u00e7','\u0100','\u0119','\u0132',
		'\u014b','\u0164','\u00cf','\u00e8','\u0101','\u011a','\u0133','\u014c','\u0165','\u00d0',
		'\u00e9','\u0102','\u011b','\u0134','\u014d','\u0166','\u00d1','\u00ea','\u0103','\u011c',
		'\u0135','\u014e','\u0167',
		//ÒëĄĝĶŏŨÓìąĞķŐũÔíĆğĸőŪÕîćĠĹŒūÖïĈġĺœŬ×ðĉĢĻŔŭØñĊģļŕŮÙòċĤĽŖůÚóČĥľŗŰ
		'\u00d2','\u00eb','\u0104','\u011d','\u0136','\u014f','\u0168','\u00d3','\u00ec','\u0105',
		'\u011e','\u0137','\u0150','\u0169','\u00d4','\u00ed','\u0106','\u011f','\u0138','\u0151',
		'\u016a','\u00d5','\u00ee','\u0107','\u0120','\u0139','\u0152','\u016b','\u00d6','\u00ef',
		'\u0108','\u0121','\u013a','\u0153','\u016c','\u00d7','\u00f0','\u0109','\u0122','\u013b',
		'\u0154','\u016d','\u00d8','\u00f1','\u010a','\u0123','\u013c','\u0155','\u016e','\u00d9',
		'\u00f2','\u010b','\u0124','\u013d','\u0156','\u016f','\u00da','\u00f3','\u010c','\u0125',
		'\u013e','\u0157','\u0170',
		//ÛôčĦĿŘűÜõĎħŀřŲÝöďĨŁŚųÞ÷ĐĩłśŴßøđĪŃŜŵàùĒīńŝŶáúēĬŅŞŷ
		'\u00db','\u00f4','\u010d','\u0126','\u013f','\u0158','\u0171','\u00dc','\u00f5','\u010e',
		'\u0127','\u0140','\u0159','\u0172','\u00dd','\u00f6','\u010f','\u0128','\u0141','\u015a',
		'\u0173','\u00de','\u00f7','\u0110','\u0129','\u0142','\u015b','\u0174','\u00df','\u00f8',
		'\u0111','\u012a','\u0143','\u015c','\u0175','\u00e0','\u00f9','\u0112','\u012b','\u0144',
		'\u015d','\u0176','\u00e1','\u00fa','\u0113','\u012c','\u0145','\u015e','\u0177'};
	
	/*
	 * HTML entities ISO-88591
	 * see for reference http://www.w3.org/TR/html4/sgml/entities.html#iso-88591
	 */
	public static final String HTML_ENTITIES_UTF[][] = {
		//ISO 8859-1 Symbol Entities
		{"\u00a1","&iexcl;"},{"\u00a2","&cent;"},{"\u00a3","&pound;"},{"\u00a4","&curren;"},
		{"\u00a5","&yen;"},{"\u00a6","&brvbar;"},{"\u00a7","&sect;"},{"\u00a8","&uml;"},
		{"\u00a9","&copy;"},{"\u00aa","&ordf;"},{"\u00ab","&laquo;"},{"\u00ac","&not;"},
		{"\u00ad","&shy;"},{"\u00ae","&reg;"},{"\u00af","&macr;"},
		{"\u00b0","&deg;"},{"\u00b1","&plusmn;"},{"\u00b2","&sup2;"},{"\u00b3","&sup3;"},
		{"\u00b4","&acute;"},{"\u00b5","&micro;"},{"\u00b6","&para;"},{"\u00b7","&middot;"},
		{"\u00b8","&cedil;"},{"\u00b9","&sup1;"},{"\u00ba","&ordm;"},{"\u00bb","&raquo;"},
		{"\u00bc","&frac14;"},{"\u00bd","&frac12;"},{"\u00be","&frac34;"},{"\u00bf","&iquest;"},
		//ISO 8859-1 Character Entities
		{"\u00c0","&Agrave;"},{"\u00c1","&Aacute;"},{"\u00c2","&Acirc;"},{"\u00c3","&Atilde;"},
		{"\u00c4","&Auml;"},{"\u00c5","&Aring;"},{"\u00c6","&AElig;"},{"\u00c7","&Ccedil;"},
		{"\u00c8","&Egrave;"},{"\u00c9","&Eacute;"},{"\u00ca","&Ecirc;"},{"\u00cb","&Euml;"},
		{"\u00cc","&Igrave;"},{"\u00cd","&Iacute;"},{"\u00ce","&Icirc;"},{"\u00cf","&Iuml;"},
		{"\u00d0","&ETH;"},{"\u00d1","&Ntilde;"},{"\u00d2","&Ograve;"},{"\u00d3","&Oacute;"},
		{"\u00d4","&Ocirc;"},{"\u00d5","&Otilde;"},{"\u00d6","&Ouml;"},{"\u00d7","&times;"},
		{"\u00d8","&Oslash;"},{"\u00d9","&Ugrave;"},{"\u00da","&Uacute;"},{"\u00db","&Ucirc;"},
		{"\u00dc","&Uuml;"},{"\u00dd","&Yacute;"},{"\u00de","&THORN;"},{"\u00df","&szlig;"},
		{"\u00e0","&agrave;"},{"\u00e1","&aacute;"},{"\u00e2","&acirc;"},{"\u00e3","&atilde;"},
		{"\u00e4","&auml;"},{"\u00e5","&aring;"},{"\u00e6","&aelig;"},{"\u00e7","&ccedil;"},
		{"\u00e8","&egrave;"},{"\u00e9","&eacute;"},{"\u00ea","&ecirc;"},{"\u00eb","&euml;"},
		{"\u00ec","&igrave;"},{"\u00ed","&iacute;"},{"\u00ee","&icirc;"},{"\u00ef","&iuml;"},
		{"\u00f0","&eth;"},{"\u00f1","&ntilde;"},
		{"\u00f2","&ograve;"},{"\u00f3","&oacute;"},{"\u00f4","&ocirc;"},{"\u00f5","&otilde;"},
		{"\u00f6","&ouml;"},{"\u00f7","&divide;"},{"\u00f8","&oslash;"},
		{"\u00f9","&ugrave;"},{"\u00fa","&uacute;"},{"\u00fb","&ucirc;"},{"\u00fc","&uuml;"},
		{"\u00fd","&yacute;"},{"\u00fe","&thorn;"},{"\u00ff","&yuml;"},
		//Greek
		{"\u0391","&Alpha;"},{"\u0392","&Beta;"},{"\u0393","&Gamma;"},{"\u0394","&Delta;"},
		{"\u0395","&Epsilon;"},{"\u0396","&Zeta;"},{"\u0397","&Eta;"},{"\u0398","&Theta;"},
		{"\u0399","&Iota;"},{"\u039a","&Kappa;"},{"\u039b","&Lambda;"},{"\u039c","&Mu;"},
		{"\u039d","&Nu;"},{"\u039e","&Xi;"},{"\u039f","&Omicron;"},{"\u03a0","&Pi;"},
		{"\u03a1","&Rho;"},{"\u03a3","&Sigma;"},{"\u03a4","&Tau;"},{"\u03a5","&Upsilon;"},
		{"\u03a6","&Phi;"},{"\u03a7","&Chi;"},{"\u03a8","&Psi;"},{"\u03a9","&Omega;"},
		{"\u03b1","&alpha;"},{"\u03b2","&beta;"},{"\u03b3","&gamma;"},{"\u03b4","&delta;"},
		{"\u03b5","&epsilon;"},{"\u03b6","&zeta;"},{"\u03b7","&eta;"},{"\u03b8","&theta;"},
		{"\u03b9","&iota;"},{"\u03ba","&kappa;"},{"\u03bb","&lambda;"},{"\u03bc","&mu;"},
		{"\u03bd","&nu;"},{"\u03be","&xi;"},{"\u03bf","&omicron;"},{"\u03c0","&pi;"},
		{"\u03c1","&rho;"},{"\u03c2","&sigmaf;"},{"\u03c3","&sigma;"},{"\u03c4","&tau;"},
		{"\u03c5","&upsilon;"},{"\u03c6","&phi;"},{"\u03c7","&chi;"},{"\u03c8","&psi;"},
		{"\u03c9","&omega;"},{"\u03d1","&thetasym;"},{"\u03d2","&upsih;"},{"\u03d6","&piv;"},
		//General Punctuation
		{"\u2022","&bull;"},{"\u2026","&hellip;"},{"\u2032","&prime;"},{"\u2033","&Prime;"},
		{"\u203e","&oline;"},{"\u2044","&frasl;"},
		//Letterlike Symbols
		{"\u2118","&weierp;"},{"\u2111","&image;"},{"\u211c","&real;"},{"\u2122","&trade;"},
		{"\u2135","&alefsym;"},
		//Arrows
		{"\u2190","&larr;"},{"\u2191","&uarr;"},{"\u2192","&rarr;"},{"\u2193","&darr;"},
		{"\u2194","&harr;"},{"\u21b5","&crarr;"},{"\u21d0","&lArr;"},{"\u21d1","&uArr;"},
		{"\u21d2","&rArr;"},{"\u21d3","&dArr;"},{"\u21d4","&hArr;"},
		//Mathematical Operators
		{"\u2200","&forall;"},{"\u2202","&part;"},{"\u2203","&exist;"},{"\u2205","&empty;"},
		{"\u2207","&nabla;"},{"\u2208","&isin;"},{"\u2209","&notin;"},{"\u220b","&ni;"},
		{"\u220f","&prod;"},{"\u2211","&sum;"},{"\u2212","&minus;"},{"\u2217","&lowast;"},
		{"\u221a","&radic;"},{"\u221d","&prop;"},{"\u221e","&infin;"},{"\u2220","&ang;"},
		{"\u2227","&and;"},{"\u2228","&or;"},{"\u2229","&cap;"},{"\u222a","&cup;"},
		{"\u222b","&int;"},{"\u2234","&there4;"},{"\u223c","&sim;"},{"\u2245","&cong;"},
		{"\u2248","&asymp;"},{"\u2260","&ne;"},{"\u2261","&equiv;"},{"\u2264","&le;"},
		{"\u2265","&ge;"},{"\u2282","&sub;"},{"\u2283","&sup;"},{"\u2284","&nsub;"},
		{"\u2286","&sube;"},{"\u2287","&supe;"},{"\u2295","&oplus;"},{"\u2297","&otimes;"},
		{"\u22a5","&perp;"},{"\u22c5","&sdot;"},
		//Miscellaneous Technical
		{"\u2308","&lceil;"},{"\u2309","&rceil;"},{"\u230a","&lfloor;"},{"\u230b","&rfloor;"},
		{"\u2329","&lang;"},{"\u232a","&rang;"},
		//Geometric Shapes
		{"\u25ca","&loz;"},{"\u2660","&spades;"},{"\u2663","&clubs;"},{"\u2665","&hearts;"},
		{"\u2666","&diams;"},
		//Latin Extended-A
		{"\u0152","&OElig;"},{"\u0153","&oelig;"},{"\u0160","&Scaron;"},{"\u0161","&scaron;"},
		{"\u0178","&Yuml;"},
		//Spacing Modifier Letters
		{"\u02c6","&circ;"},{"\u02dc","&tilde;"},
		//General Punctuation
		{"\u2002","&ensp;"},{"\u2003","&emsp;"},{"\u2009","&thinsp;"},{"\u200c","&zwnj;"},
		{"\u200d","&zwj;"},{"\u200e","&lrm;"},{"\u200f","&rlm;"},{"\u2013","&ndash;"},
		{"\u2014","&mdash;"},{"\u2018","&lsquo;"},{"\u2019","&rsquo;"},{"\u201a","&sbquo;"},
		{"\u201c","&ldquo;"},{"\u201d","&rdquo;"},{"\u201e","&bdquo;"},{"\u2020","&dagger;"},
		{"\u2021","&Dagger;"},{"\u2030","&permil;"},{"\u2039","&lsaquo;"},{"\u203a","&rsaquo;"},
		{"\u20ac","&euro;"}
	};
}
