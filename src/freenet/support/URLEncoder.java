package freenet.support;

public class URLEncoder {
  // Moved here from fproxy by amphibian
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
