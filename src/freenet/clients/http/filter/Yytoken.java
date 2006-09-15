/*
  Yytoken.java / Freenet
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

package freenet.clients.http.filter;

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
  
  Yytoken (int index, String text, int line, int charBegin, int charEnd) {
     m_index = index;
    m_text = text;
    m_line = line;
    m_charBegin = charBegin;
    m_charEnd = charEnd;
  }

  public String toString() {
    return "Text   : "+m_text+
           "\nindex : "+m_index+
           "\nline  : "+m_line+
           "\ncBeg. : "+m_charBegin+
           "\ncEnd. : "+m_charEnd;
  }
}

