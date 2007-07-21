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
package freenet.utils;

import junit.framework.TestCase;

/**
 * Utility class used throught test cases classes
 * 
 * @author Alberto Bacchelli &lt;sback@freenetproject.org&gt;
 */
public final class UTFUtil extends TestCase {

	public void testFake() {
		
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
}
