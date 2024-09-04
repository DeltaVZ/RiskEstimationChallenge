package utils;

/**
 * Class that holds keys used by Mongo
 */
public final class MongoKeys {
    public static final String ID = "_id";
    public static final String NEWEST_RISK_ESTIMATION = "newest_risk_estimation";
    public static final String NEWEST_RISK_PREDICTION = "newest_risk_prediction";
    public static final String SAT1_NORAD_ID = "sat1_norad_id";
    public static final String SAT2_NORAD_ID = "sat2_norad_id";
    public static final String CONJUNCTION_ID = "conjunction_id";
    public static final String SUGGESTED = "suggested";
    public static final String RISK_TREND = "risk_trend";
    public static final String COLLISION_PROBABILITY = "collision_probability";
    public static final String TIME_TO_TCA = "time_to_tca";

    private MongoKeys() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}