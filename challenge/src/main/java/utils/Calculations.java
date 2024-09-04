package utils;

public class Calculations {

    private Calculations() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static native void analyze_theta
            (
                    double[] time_to_tca_0,
                    double[] coll_prob_0,
                    double[] time_to_tca_1,
                    double[] coll_prob_1,
                    double[] result_theta
            );

    public static native void adjust_coll_prob
            (
                    double[] time_to_tca_0,
                    double[] coll_prob_0,
                    double[] time_to_tca_1,
                    double[] coll_prob_1,
                    double[] result_adjusted_value
            );

    public static native boolean check_theta(double[] theta);
}
