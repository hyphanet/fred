/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.filter;

//~--- non-JDK imports --------------------------------------------------------

import freenet.l10n.NodeL10n;

//~--- JDK imports ------------------------------------------------------------

import java.io.UnsupportedEncodingException;

public class UnknownCharsetException extends DataFilterException {
    private static final long serialVersionUID = 1L;
    public final String charset;

    private UnknownCharsetException(String warning, String warning2, String string, String charset) {
        super(warning, warning2, string);
        this.charset = charset;
    }

    public static UnknownCharsetException create(UnsupportedEncodingException e, String charset) {
        String explTitle = l10nDF("unknownCharsetTitle");
        String expl = l10nDF("unknownCharset");
        String warning = l10nDF("warningUnknownCharsetTitle", "charset", charset);

        return new UnknownCharsetException(warning, warning, explTitle + " " + expl, charset);
    }

    private static String l10nDF(String key) {

        // All the strings here are generic
        return NodeL10n.getBase().getString("ContentDataFilter." + key);
    }

    private static String l10nDF(String key, String pattern, String value) {

        // All the strings here are generic
        return NodeL10n.getBase().getString("ContentDataFilter." + key, pattern, value);
    }
}
