/*
  PaddingSpeedTest.java / Freenet
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

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.support.ByteFormat;

/**
 * Test the speed of RNGs and hashes.
 */
public class PaddingSpeedTest {

    public static void main(String[] args) throws NoSuchAlgorithmException, DigestException {
        MessageDigest md160 = MessageDigest.getInstance("SHA-1");
        MessageDigest md256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md384 = MessageDigest.getInstance("SHA-384");
        MessageDigest md512 = MessageDigest.getInstance("SHA-512");
        MessageDigest[] mds = new MessageDigest[] { md160, md256, md384, md512 };
        int[] sizes = new int[] { 160, 256, 384, 512 };
        for(int i=0;i<4;i++) {
            long timeStart = System.currentTimeMillis();
            int bits = sizes[i];
            MessageDigest md = mds[i];
            System.out.println("Algorithm "+i+": "+bits+" bits");
            int bytes = bits/8;
            byte[] buf = new byte[bytes];
            for(int x=0;x<buf.length;x++) buf[x] = 0;
            for(int j=0;j<512;j++) {
                for(int k=0;k<1024;k++) {
                    md.update(buf);
                    md.digest(buf, 0, buf.length);
                }
            }
            long timeEnd = System.currentTimeMillis();
            long interval = timeEnd - timeStart;
            System.out.println("Total time: "+interval);
            int bytesTotal = 512 * 1024 * bytes;
            printStats("SHA-"+bits, bytesTotal, interval);
        }
        // And a plain RNG
        Random r = new Random();
        long l = 0;
        long timeStart = System.currentTimeMillis();
        for(int q=0;q<50;q++) {
        for(int i=0;i<1024;i++) {
            for(int j=0;j<1024;j++) {
                l = l ^ r.nextLong();
            }
        }
        }
        long timeEnd = System.currentTimeMillis();
        long interval = timeEnd - timeStart;
        int bytesTotal = 50 * 1024 * 1024 * 8;
        printStats("java.util.Random", bytesTotal, interval);
        // Now a more interesting RNG
        byte[] buf = new byte[32]; // init from SHA-256
        r.nextBytes(buf);
        int[] seed = new int[8];
        for(int i=0;i<8;i++) {
            int x = buf[i*4] & 0xff;
            x = x << 8 + (buf[i*4+1] & 0xff);
            x = x << 8 + (buf[i*4+2] & 0xff);
            x = x << 8 + (buf[i*4+3] & 0xff);
            seed[i] = x;
        }
        MersenneTwister mt;
        mt = new MersenneTwister(seed);
        timeStart = System.currentTimeMillis();
        for(int q=0;q<50;q++) {
        for(int i=0;i<1024;i++) {
            for(int j=0;j<1024;j++) {
                l = l ^ mt.nextLong();
            }
        }
        }
        timeEnd = System.currentTimeMillis();
        interval = timeEnd - timeStart;
        bytesTotal = 50 * 1024 * 1024 * 8;
        printStats("Mersenne Twister", bytesTotal, interval);
    }

    /**
     * @param bytesTotal
     * @param interval
     */
    private static void printStats(String name, int bytesTotal, long interval) {
        double rate = bytesTotal / ((double)interval/1000);
        System.out.println(name+": "+bytesTotal+" in "+interval+"ms = "+ByteFormat.format((long)rate,false)+"/s");
    }
}
