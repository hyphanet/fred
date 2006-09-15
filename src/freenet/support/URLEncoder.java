/*
  URLEncoder.java / Freenet
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support;

public class URLEncoder {
  // Moved here from FProxy by amphibian
  final static String safeURLCharacters = "@*-./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";

  /**
   * Encode a string for inclusion in HTML tags
   *
   * @param  URL  String to encode
   * @return      HTML-safe version of string
   */
  public final static String encode(String URL) {
    StringBuffer enc = new StringBuffer(URL.length());
    for (int i = 0; i < URL.length(); ++i) {
      char c = URL.charAt(i);
      if (safeURLCharacters.indexOf(c) >= 0) {
        enc.append(c);
      } else {
        // Too harsh.
        // if (c < 0 || c > 255)
        //    throw new RuntimeException("illegal code "+c+" of char '"+URL.charAt(i)+"'");
        // else

        // Just keep lsb like:
        // http://java.sun.com/j2se/1.3/docs/api/java/net/URLEncoder.html
        c = (char) (c & '\u00ff');
        if (c < 16) {
          enc.append("%0");
        } else {
          enc.append("%");
        }
        enc.append(Integer.toHexString(c));
      }
    }
    return enc.toString();
  }

}
