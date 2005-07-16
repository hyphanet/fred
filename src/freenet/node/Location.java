package freenet.node;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import freenet.crypt.RandomSource;

/**
 * @author amphibian
 *
 * Location of a node in the keyspace. ~= specialization.
 * Simply a number from 0.0 to 1.0.
 */
public class Location {
    private double loc;
    
    private Location(double location) {
        loc = location;
    }

    public double getValue() {
        return loc;
    }

    /**
     * Read a Location from a file
     * @param filename The name of the file to read the Location from.
     */
    public static Location read(String filename) throws IOException {
        FileInputStream fis = new FileInputStream(filename);
        InputStreamReader r = new InputStreamReader(fis);
        BufferedReader br = new BufferedReader(r);
        String line = br.readLine();
        double loc = Double.parseDouble(line);
        fis.close();
        return new Location(loc);
    }

    /**
     * @return A random Location to initialize the node to.
     */
    public static Location randomInitialLocation(RandomSource r) {
        return new Location(r.nextDouble());
    }

    /**
     * Write the Location to disk.
     * @param filename Name of file to write to.
     * @throws IOException If the write failed.
     */
    public void write(String filename) throws IOException {
        FileOutputStream fos = new FileOutputStream(filename);
        OutputStreamWriter w = new OutputStreamWriter(fos);
        w.write(Double.toString(loc)+"\n");
        fos.close();
    }
}
