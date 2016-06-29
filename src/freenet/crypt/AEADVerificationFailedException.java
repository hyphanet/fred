package freenet.crypt;

import java.io.IOException;

/** Thrown when the final MAC fails on an AEADInputStream. */
public class AEADVerificationFailedException extends IOException {
    private static final long serialVersionUID = 4850585521631586023L;
}
