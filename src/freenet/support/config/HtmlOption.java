package freenet.support.config;

import javax.servlet.http.*;

/**
 * An option to be set or configured in a HTML document
 **/
public abstract class HtmlOption extends Option {

    public HtmlOption(String name, char abbrev, int numArgs, int sortOrder) {
        super(name, abbrev, numArgs, sortOrder);
    }

    /**
     * Obtain a brief HTML description of this option
     * @return A HTML fragment
     **/
    public abstract String briefDescription();

    /**
     * Obtain a detailed HTML description of this option
     * @return A HTML fragment
     **/
    public abstract String detailedDescription();

    /**
     * Obtain a fragment of a HTML form containing the
     * information for this option (pre-set to the option's
     * current values)
     * @return A HTML fragment
     **/
    public abstract String formElements();

    /**
     * Checks to ensure that the values for this form are
     * valid, if not, return a string explaining the error
     * @return A string explaining the error, or null if 
     *         everything is ok
     **/
    public abstract String checkForm(HttpServletRequest req);

    /**
     * Set the value of this option to the values set in the
     * HttpServletRequest.
     **/
    // TODO: This should assert that checkForm(HSR req) returns
    //       null
    public abstract void processForm(HttpServletRequest req);
}






