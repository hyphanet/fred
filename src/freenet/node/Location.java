package freenet.node;

import java.io.BufferedReader;
import java.io.FileInputStream;
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
    private int hashCode;
    
    private Location(double location) {
        setValue(location);
    }

    public Location(String init) throws FSParseException {
        try {
            setValue(Double.parseDouble(init));
        } catch (NumberFormatException e) {
            throw new FSParseException(e);
        }
    }

    public double getValue() {
        return loc;
    }

    /**
     * @return A random Location to initialize the node to.
     */
    public static Location randomInitialLocation(RandomSource r) {
        return new Location(r.nextDouble());
    }

    public void setValue(double newLoc) {
        if(loc < 0.0 || loc > 1.0)
            throw new IllegalArgumentException();
        this.loc = newLoc;
        long l = Double.doubleToLongBits(newLoc);
        hashCode = ((int)(l >>> 32)) ^ ((int)l);
    }
    
    public boolean equals(Object o) {
        if(o instanceof Location) {
            return ((Location)o).loc == loc;
        }
        return false;
    }
    
    public int hashCode() {
        return hashCode;
    }

    /**
     * Randomize the location.
     */
    public synchronized void randomize(RandomSource r) {
        setValue(r.nextDouble());
    }
}
