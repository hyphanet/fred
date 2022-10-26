package freenet.support;

import java.util.function.Predicate;

public class PredicateUtil {

    public static <T> Predicate<T> not(Predicate<T> target) {
        return target.negate();
    }
}
