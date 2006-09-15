/*
  PingTest.java / Freenet
  Copyright (C) amphibian
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

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import freenet.io.comm.Dispatcher;
import freenet.io.comm.DumpDispatcher;
import freenet.io.comm.Message;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketManager;
import freenet.io.comm.DMT;
import freenet.io.comm.MessageFilter;

/**
 * Ping test.
 * Just me getting used to the Dijjer-derived messaging system really.
 * Takes two parameters: ourPort and hisPort.
 * Sends a ping message every second.
 * Prints out whenever we receive a ping message.
 * @author amphibian
 */
public class PingTest {


    /**
     *
     * TODO To change the template for this generated type comment go to
     * Window - Preferences - Java - Code Generation - Code and Comments
     */
    public static class PingingDispatcher implements Dispatcher {
        public boolean handleMessage(Message m) {
            if(m.getSpec() == DMT.ping) {
                usm.send(m.getSource(), DMT.createPong(m));
                return true;
            }
            return false;
        }
    }
    
    static UdpSocketManager usm;
    
    public PingTest() {
        // not much to initialize
        super();
    }

    public static void main(String[] args) throws SocketException, UnknownHostException {
        if(args.length < 2) {
            System.err.println("Syntax: PingTest <myPort> <hisPort>");
            System.exit(1);
        }
        int myPort = Integer.parseInt(args[0]);
        int hisPort = Integer.parseInt(args[1]);
        System.out.println("My port: "+myPort+", his port: "+hisPort);
        // Set up a UdpSocketManager
        usm = new UdpSocketManager(myPort);
        usm.setDispatcher(new PingingDispatcher());
        Peer otherSide;
        otherSide = new Peer(InetAddress.getByName("127.0.0.1"), hisPort);
        while(true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            System.err.println("Sending ping");
            usm.send(otherSide, DMT.createPing());
            Message m = usm.waitFor(MessageFilter.create().setTimeout(1000).setType(DMT.pong).setSource(otherSide));
            if(m != null) {
                System.err.println("Got pong: "+m);
            }
        }
    }
}
