/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http.geoip;

public class Cache {
    short[] codes;
    int[] ips;

    public Cache(short[] codes, int[] ips) {
        this.codes = codes;
        this.ips = ips;
    }

    public short[] getCodes() {
        return codes;
    }

    public int[] getIps() {
        return ips;
    }
}
