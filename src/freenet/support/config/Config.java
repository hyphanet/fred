/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.support.config;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;

/** A registry of Options.
 * @author tavin
 */
public class Config {

    private final Hashtable options = new Hashtable();

    public String toString() {
        StringBuffer b = new StringBuffer();
        for(Enumeration e=options.keys();e.hasMoreElements();) {
            Object key = e.nextElement();
            b.append("Key: ");
            b.append(key.toString());
            Object value = options.get(key);
            b.append("\nValue: ");
            b.append(value.toString());
            b.append("\n");
        }
        return new String(b);
    }
    
    public void addOption(Option opt) {
        options.put(opt.name(), opt);
    }
    
    /** Allows specifying the type of an argument
     * with a null default value
     */
    public void addOption(String name, char abbrev, int numArgs, Class def,
                          int sortOrder) {
        addOption(new NormalOption(name, abbrev, numArgs, def, sortOrder, false));
    }

    /** Register an option with a default value.
     */
    public void addOption(String name, char abbrev, int numArgs, Object def,
                          int sortOrder) {
        addOption(new NormalOption(name, abbrev, numArgs, def,
                                   sortOrder, false));
    }

    public void addOption(String name, char abbrev, int numArgs, int def,
                          int sortOrder) {
        addOption(name, abbrev, numArgs, new Integer(def), sortOrder);
    }

    public void addOption(String name, char abbrev, int numArgs, long def,
                          int sortOrder) {
        addOption(name, abbrev, numArgs, new Long(def), sortOrder);
    }

    public void addOption(String name, char abbrev, int numArgs, double def,
                          int sortOrder) {
        addOption(name, abbrev, numArgs, new Double(def), sortOrder);
    }

    public void addOption(String name, char abbrev, int numArgs, float def,
                          int sortOrder) {
        addOption(name, abbrev, numArgs, new Float(def), sortOrder);
    }
    
    public void addOption(String name, char abbrev, int numArgs, boolean def,
                          int sortOrder) {
        addOption(name, abbrev, numArgs, new Boolean(def), sortOrder);
    }

                          

    // no abbreviation

    public void addOption(String name, int numArgs, Class def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }
    
    public void addOption(String name, int numArgs, Object def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }

    public void addOption(String name, int numArgs, int def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }

    public void addOption(String name, int numArgs, long def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }
    
    public void addOption(String name, int numArgs, double def, int sortOrder){
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }

    public void addOption(String name, int numArgs, float def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }

    public void addOption(String name, int numArgs, boolean def, int sortOrder) {
        addOption(name, (char) 0, numArgs, def, sortOrder);
    }

    // with installation

    public void addOption(String name, int numArgs, Class def, int sortOrder,
                          boolean installation) {
        addOption(new NormalOption(name, '\000', 1, def, sortOrder, installation));
    }
    
    public void addOption(String name, int numArgs, Object def, int sortOrder,
                          boolean installation) {
        addOption(new NormalOption(name, '\000', 1, def, sortOrder, installation));
    }

    public void addOption(String name, int numArgs, int def, int sortOrder, boolean install) {
        addOption(name, numArgs, new Integer(def), sortOrder, install);
    }

    public void addOption(String name, int numArgs, long def, int sortOrder, boolean install) {
        addOption(name, numArgs, new Long(def), sortOrder, install);
    }
    
    public void addOption(String name, int numArgs, double def, int sortOrder, boolean install){
        addOption(name, numArgs, new Double(def), sortOrder, install);
    }

    public void addOption(String name, int numArgs, float def, int sortOrder, boolean install) {
        addOption(name, numArgs, new Float(def), sortOrder, install);
    }

    public void addOption(String name, int numArgs, boolean def, int sortOrder, boolean install) {
        addOption(name, numArgs, new Boolean(def), sortOrder, install);
    }
    
    /**
     * Allows an option to be set as expert, meaning it will
     * be skipped in normal configuration.
     */
    public void setExpert(String name, boolean isExpert) {
        ((Option) options.get(name)).isExpert = isExpert;
    }

       /**
        * Allows an option to be set as deprecated, meaning it will
        * never be written, but will be read. Implies expert.
        */
    public void setDeprecated(String name, boolean isDeprecated) {
    	if(isDeprecated) setExpert(name, true);
        ((Option) options.get(name)).isDeprecated = isDeprecated;
    }
    
    /**
     * Describes the argument(s) to the option.
     */
    public void argDesc(String name, String argDesc) {
        ((Option) options.get(name)).argDesc = argDesc;
    }
    
    /**
     * Brief summary of the option.
     */
    public void shortDesc(String name, String shortDesc) {
        ((Option) options.get(name)).shortDesc = shortDesc;
    }
    
    /**
     * Full description of the option (goes in the config file).
     */
    public void longDesc(String name, String d1) {
        ((Option) options.get(name)).longDesc = new String[] { d1 };
    }
    
    public void longDesc(String name, String d1, String d2) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2 };
    }
    
    public void longDesc(String name, String d1, String d2, String d3) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3 };
    }
    
    public void longDesc(String name, String d1, String d2, String d3, String d4) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4 };
    }
    
    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6, String d7) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6, d7 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6, String d7, String d8) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6, d7, d8 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6, String d7, String d8, String d9) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6, d7, d8, d9 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6, String d7, String d8, String d9, String d10) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6, d7, d8, d9, d10 };
    }

    public void longDesc(String name, String d1, String d2, String d3, String d4, String d5, String d6, String d7, String d8, String d9, String d10, String d11) {
        ((Option) options.get(name)).longDesc = new String[] { d1, d2, d3, d4, d5, d6, d7, d8, d9, d10, d11 };
    }

    /** @return a Params.Option[] suitable for constructing a Params
     */
    public Option[] getOptions() {
        Option[] optArray = new Option[options.size()];
        Enumeration opts = options.elements();
        for (int i=0; i<optArray.length; ++i)
            optArray[i] = (Option) opts.nextElement();
        return optArray;
    }
    
    public Option getOption(String name){
        return (Option)options.get(name);
    }     
    
    public void printUsage(PrintStream out) {
        
        Option[] opts = getOptions();
        Arrays.sort(opts);
        
        int maxWidth = 0;
        for (int i=0; i<opts.length; ++i) {
            int w = colWidth(opts[i]);
            if (maxWidth < w) maxWidth = w;
        }

        for (int i=0; i<opts.length; ++i) {
            
            int w = colWidth(opts[i]);
            
            String argDesc   = opts[i].argDesc;
            if (argDesc == null) argDesc = "";
            else                 argDesc = " " + argDesc;  

            String shortDesc = opts[i].shortDesc;
            if (shortDesc == null) shortDesc = "(undocumented)";
            
            StringBuffer line = new StringBuffer();
            if (opts[i].abbrev() != (char) 0)
                line.append("-" + opts[i].abbrev() + "|");
            line.append("--" + opts[i].name() + argDesc);
            while (w++ < maxWidth) line.append(' ');  // (' ' x maxWidth-w) .. ha, i wish
            out.println("  " + line + "    " + shortDesc);
        }
    }

    public void printManual(PrintStream out) {
        printManual(new PrintWriter(out));
    }
    /**
     * Prints manual entries in an html format.
     */
    public void printManual(PrintWriter out) {
        out.println("<table width=\"100%\">");

        Option[] opts = getOptions();
        Arrays.sort(opts);
        for (int i=0; i<opts.length; ++i) {
            out.println("<tr>");
            String name = htmlEnc(opts[i].name());
            out.print("<td><i>Name:</i></td><td><b>" + 
                      name + "</b>&nbsp;&nbsp;&nbsp;(--" +
                      name);
            if (opts[i].abbrev() != (char) 0)
                out.print(" | -" + opts[i].abbrev());
            out.println(")</td>");
            out.println("</tr><tr>");
            String argDesc = opts[i].argDesc;
            out.println("<td><i>Arguments:</i></td><td>" +
                        (argDesc == null ? "&nbsp;" : htmlEnc(argDesc)) + 
                        "</td>");
            out.println("</tr><tr>");
            Object dfault = opts[i].defaultValue();
            if (opts[i] instanceof RandomPortOption) dfault = "<random>";
            out.println("<td><i>Default val:</i></td><td>" + 
                        (dfault == null ? "&nbsp;": htmlEnc(dfault.toString()))
                        + "</td>");
            out.println("</tr><tr>");
            out.println("<td><i>Description:&nbsp;&nbsp;</i></td><td>");
            if (opts[i].longDesc != null) {
                for (int j = 0 ; j < opts[i].longDesc.length ; j++) {
                    out.println(htmlEnc(opts[i].longDesc[j]));
                }
            } else if (opts[i].shortDesc != null) {
                String desc = opts[i].shortDesc;
                if (!desc.endsWith("."))
                    desc = desc + '.';
                out.println(desc);
            } else {
                out.println("(undocumented)");
            }
            out.println("</td>");
            out.println("</tr><tr><td colspan=2><hr></td></tr>");
        }
        out.println("</table>");
    }

    private static int colWidth(Option o) {
        int w = 2 + o.name().length();       // '--name'
        if (o.abbrev() != (char) 0) w += 3;  // '-x|'
        if (o.argDesc != null)
            w += 1 + o.argDesc.length();     // ' desc'
        return w;
    }

    public static String htmlEnc(String s) {
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        int first = lt == -1 ? gt : (gt == -1 ? lt : Math.min(lt, gt));
        if (first == -1) {
            return s;
        } else {
            return s.substring(0, first) + (first == lt ? "&lt;" : "&gt;") +
                htmlEnc(s.substring(first + 1));
        }
    }
}


