/*
  FrostMessageObject.java / Frost
  Copyright (C) 2003-2006  Frost Project <jtcfrost.sourceforge.net>
  Public Domain 2006  VolodyA! V A <volodya@whengendarmesleeps.org>

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

package freenet.frost.message;

public final class FrostBoard {
	
	public static final int MAX_NAME_LENGTH = 64;
    
    private String boardName = null;

    private String publicKey = null;
    private String privateKey = null;
    
    /**
     * Constructs a new FrostBoardObject wich is a Board.
     */
    public FrostBoard(String name) {
        boardName = name;
    }

    /**
     * Constructs a new FrostBoardObject wich is a Board.
     * @param name
     * @param pubKey
     * @param privKey
     */
    public FrostBoard(String name, String pubKey, String privKey) {
        this(name);
        setPublicKey(pubKey);
        setPrivateKey(privKey);
    }

    public String getName() {
        return boardName;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public boolean isPublicBoard() {
        if (publicKey == null && privateKey == null)
            return true;
        return false;
    }

    public boolean isReadAccessBoard() {
        if (publicKey != null && privateKey == null) {
            return true;
        }
        return false;
    }

    public boolean isWriteAccessBoard() {
        if (publicKey != null && privateKey != null) {
            return true;
        }
        return false;
    }

    public void setPrivateKey(String val) {
        if (val.length()<5) val= null;
    	if (val != null) {
            val = val.trim();
        }
        privateKey = val;
    }

    public void setPublicKey(String val) {
    	if (val.length()<5) val= null;
        if (val != null) {
            val = val.trim();
        }
        publicKey = val;
    }
}
