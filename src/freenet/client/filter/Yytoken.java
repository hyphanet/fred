/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.filter;

/**
 * Parsing token.
 *
 * @author devrandom@hyper.to
 */
class Yytoken {
    public int m_index;
    public String m_text;
    public int m_line;
    public int m_charBegin;
    public int m_charEnd;

    Yytoken(int index, String text, int line, int charBegin, int charEnd) {
        m_index = index;
        m_text = text;
        m_line = line;
        m_charBegin = charBegin;
        m_charEnd = charEnd;
    }

    @Override
    public String toString() {
        return "Text   : " + m_text + "\nindex : " + m_index + "\nline  : " + m_line + "\ncBeg. : " + m_charBegin
               + "\ncEnd. : " + m_charEnd;
    }
}
