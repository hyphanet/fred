package freenet.support.config;

import freenet.Core;
import java.io.IOException;
import java.net.ServerSocket;

/**
 * Generates as a default value a random port that is available when
 * defaultValue() is called. These are always "installation" options.
 *
 * @author oskar
 */

public class RandomPortOption extends Option {

    public RandomPortOption(String name, char abbrev, int numArgs, 
                            int sortOrder) {
        super(name, abbrev, numArgs, sortOrder);
    }
    
    public RandomPortOption(String name, int numArgs, 
                            int sortOrder) {
        super(name, '\000', numArgs, sortOrder);
    }
    

    public Object defaultValue() {
        int port;
        boolean worked = false;
        int i = 0;
        do {
            port = 5001 + Math.abs(Core.getRandSource().nextInt()) % (65536 - 5001);
            try {
                ServerSocket ss = new ServerSocket(port);
                worked = true;
            } catch (IOException e) {
            }
        } while (i < 50 && !worked);
        return new Integer(port);
    }

    public Class defaultClass() {
        return Integer.class;
    }

    public boolean isInstallation() {
        return true;
    }


}
