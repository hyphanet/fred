/*
  DatastoreTest.java / Freenet
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

package test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import freenet.keys.CHKDecodeException;
import freenet.keys.CHKEncodeException;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.CHKBlock;
import freenet.keys.ClientCHKBlock;
import freenet.keys.FreenetURI;
import freenet.store.FreenetStore;
import freenet.support.Logger;

/**
 * Create, or load, a datastore.
 * Command line interface, to do either:
 * a) Enter data, is encoded, store in datastore, return key.
 * b) Retrieve data by key.
 */
public class DatastoreTest {

    public static void main(String[] args) throws Exception {
        // Setup datastore
        FreenetStore fs = new FreenetStore("datastore", "headerstore", 1024);
        // Setup logging
        Logger.setupStdoutLogging(Logger.DEBUG, "");
        printHeader();
        // Read command, and data
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            String line = reader.readLine();
            if(line.toUpperCase().startsWith("GET:")) {
                // Should have a key next
                String key = line.substring("GET:".length());
                while(key.length() > 0 && key.charAt(0) == ' ')
                    key = key.substring(1);
                while(key.length() > 0 && key.charAt(key.length()-1) == ' ')
                    key = key.substring(0, key.length()-2);
                Logger.normal(DatastoreTest.class, "Key: "+key);
                FreenetURI uri = new FreenetURI(key);
                ClientCHK chk = new ClientCHK(uri);
                CHKBlock block;
                try {
                    block = fs.fetch(chk.getNodeCHK());
                } catch (CHKVerifyException e1) {
                    Logger.error(DatastoreTest.class, "Did not verify: "+e1, e1);
                    continue;
                }
                if(block == null) {
                    System.out.println("Not found in store: "+chk.getURI());
                } else {
                    // Decode it
                    byte[] decoded;
                    try {
                        decoded = block.decode(chk);
                    } catch (CHKDecodeException e) {
                        Logger.error(DatastoreTest.class, "Cannot decode: "+e, e);
                        continue;
                    }
                    System.out.println("Decoded data:\n");
                    System.out.println(new String(decoded));
                }
            } else if(line.toUpperCase().startsWith("QUIT")) {
                System.out.println("Goodbye.");
                System.exit(0);
            } else if(line.toUpperCase().startsWith("PUT:")) {
                line = line.substring("PUT:".length());
                while(line.length() > 0 && line.charAt(0) == ' ')
                    line = line.substring(1);
                while(line.length() > 0 && line.charAt(line.length()-1) == ' ')
                    line = line.substring(0, line.length()-2);
                String content;
                if(line.length() > 0) {
                    // Single line insert
                    content = line;
                } else {
                    // Multiple line insert
                    StringBuffer sb = new StringBuffer(1000);
                    while(true) {
                        line = reader.readLine();
                        if(line.equals(".")) break;
                        sb.append(line).append('\n');
                    }
                    content = sb.toString();
                }
                // Insert
                byte[] data = content.getBytes();
                ClientCHKBlock block;
                try {
                    block = ClientCHKBlock.encode(data);
                } catch (CHKEncodeException e) {
                    Logger.error(DatastoreTest.class, "Couldn't encode: "+e, e);
                    continue;
                }
                ClientCHK chk = block.getClientKey();
                FreenetURI uri = 
                    chk.getURI();
                fs.put(block);
                // Definitely interface
                System.out.println("URI: "+uri);
            } else {
                
            }
        }
    }

    private static void printHeader() {
        // Write header
        System.out.println("Datastore tester");
        System.out.println("----------------");
        System.out.println();
        System.out.println("Enter one of the following commands:");
        System.out.println("FETCH:<Freenet key> - fetch a key from the store");
        System.out.println("PUT:\n<text, until a . on a line by itself> - We will insert the document and return the key.");
        System.out.println("PUT:<text> - put a single line of text to a CHK and return the key.");
        System.out.println("QUIT - exit the program");
    }
}
