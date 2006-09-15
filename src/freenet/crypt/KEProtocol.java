/*
  KEProtocol.java / Freenet, Java Adaptive Network Client
  Copyright (C) Ian Clarke
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

package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the interface that must be implemented by key-exchange protocols
 * such as RSA and Diffie-Helman
 */
public abstract class KEProtocol {
    protected RandomSource randomSource;
    protected EntropySource es;

    public KEProtocol(RandomSource rs) {
	randomSource=rs;
	es=new EntropySource();
    }

    public abstract void negotiateKey(InputStream in, OutputStream out,
				      byte[] key, int offset, int len) 
				      throws IOException;
}
