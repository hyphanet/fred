/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.filter;

//~--- non-JDK imports --------------------------------------------------------

import freenet.l10n.NodeL10n;

public class UnsupportedCharsetInFilterException extends UnsafeContentTypeException {
    final private static long serialVersionUID = 3775454822229213420L;
    final String charset;

    public UnsupportedCharsetInFilterException(String string) {
        charset = string;
    }

    @Override
    public String getMessage() {
        return l10n("explanation");
    }

    @Override
    public String getHTMLEncodedTitle() {
        return l10n("title", "charset", charset);
    }

    @Override
    public String getRawTitle() {
        return l10n("title", "charset", charset);
    }

    public String l10n(String message) {
        return NodeL10n.getBase().getString("UnsupportedCharsetInFilterException." + message);
    }

    public String l10n(String message, String key, String value) {
        return NodeL10n.getBase().getString("UnsupportedCharsetInFilterException." + message, key, value);
    }
}
