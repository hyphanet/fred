package freenet.node;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Hashtable;

import freenet.support.Logger;
import freenet.support.config.Config;

/**
 * Central config object for Fred. Monitors config file for updates,
 * reads, updates, and writes config file, reads command line options,
 * notifies registered config consumers of updates and so on.
 * 
 * @author amphibian
 */
public class FredConfig {

    final String[] defaultNames;

    final Hashtable subconfigs = new Hashtable();
    
    FredConfig() {
        String[] names = new String[] { "freenet.conf", "freenet.ini" };
        if(File.pathSeparator.equals("\\")) {
            String s = names[1];
            names[1] = names[0];
            names[0] = s;
        }
        defaultNames = names;
    }
    
    /**
     * Read the command-line parameters and the freenet.conf/freenet.ini file.
     * @param args Command-line parameters from main().
     */
    public void readParams(String[] args) {
        
        // FIXME: implement.
        // At a minimum we need --paramFile.
        // Well, strictly, we don't need any params. 
        // Just pick up the freenet.conf/freenet.ini
        
        readConfigFile();
    }

    /**
     * Find and read the config file.
     */
    private void readConfigFile() {
        File f = findConfigFile();
        if(f == null) return; // No config file
        // Read the config file
        FileInputStream fis;
        try {
            fis = new FileInputStream(f);
        } catch (FileNotFoundException e) {
            Logger.error(this, "Found "+f+" but got "+e, e);
            return;
        }
        BufferedInputStream bis = new BufferedInputStream(fis);
        // FIXME: read the config file
        // TODO Auto-generated method stub
        
    }

    /**
     * Find the config file, if it exists.
     * @return The filename of the config file.
     */
    private File findConfigFile() {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Register a config directory. Update all values from config file
     * and do initial call of all callbacks.
     * @param callback The object to call when an option changes.
     * @param defaults The Config object representing the default values.
     * @param dirName The name of the config directory. Only options 
     * directly within this directory are associated with it - node.listenPort
     * belongs to node, but node.blah.xyz does not.
     */
    void register(Configgable callback, Config defaults, String dirName) {
        Subconfig sc = new Subconfig(defaults, callback);
        subconfigs.put(dirName.toLowerCase(), sc);
        update(dirName, sc);
    }
    
    /**
     * Update a given Subconfig and call all the callbacks.
     * If a callback throws an IllegalArgumentException, log it at ERROR,
     * and possibly on the UAM.
     * @param dirName The name of the Subconfig
     * @param sc The Subconfig itself.
     */
    private void update(String dirName, Subconfig sc) {
        // TODO Auto-generated method stub
        
    }

    public void finishedInit() {
        // TODO: implement
        // Will grumble about any options not yet accounted for
    }
    
    class Subconfig {
        
        /**
         * Create a Subconfig.
         * @param defaults The Config object for this directory.
         * @param callback The object to call when something changes.
         */
        public Subconfig(Config defaults, Object callback) {
            this.defaults = defaults;
            this.callback = callback;
        }
        Config defaults;
        Object callback;
    }
}
