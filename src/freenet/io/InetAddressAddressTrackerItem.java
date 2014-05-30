/*
 * Copyright 2007 Freenet Project Inc.
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */



package freenet.io;

//~--- non-JDK imports --------------------------------------------------------

import freenet.node.FSParseException;

import freenet.support.SimpleFieldSet;

//~--- JDK imports ------------------------------------------------------------

import java.net.InetAddress;
import java.net.UnknownHostException;

public class InetAddressAddressTrackerItem extends AddressTrackerItem {
    public final InetAddress addr;

    public InetAddressAddressTrackerItem(SimpleFieldSet fs) throws FSParseException {
        super(fs);

        try {
            addr = InetAddress.getByName(fs.getString("Address"));
        } catch (UnknownHostException e) {
            throw(FSParseException) new FSParseException("Unknown domain name in Address: " + e).initCause(e);
        }
    }

    public InetAddressAddressTrackerItem(long timeDefinitelyNoPacketsReceived, long timeDefinitelyNoPacketsSent,
            InetAddress addr) {
        super(timeDefinitelyNoPacketsReceived, timeDefinitelyNoPacketsSent);
        this.addr = addr;
    }

    @Override
    public SimpleFieldSet toFieldSet() {
        SimpleFieldSet fs = super.toFieldSet();

        fs.putOverwrite("Address", addr.getHostAddress());

        return fs;
    }
}
