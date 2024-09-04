package challenge;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.and;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.Calculations;
import utils.MongoKeys;

import java.util.List;
import java.util.Objects;

public class ChallengeClass {

    private static final Logger logger = LoggerFactory.getLogger(ChallengeClass.class);
    /**
     * The maximum Norad_ID that the satellites should have for the adjustment of the suggestions to happen
     */
    public static final short MAXIMUM_NORAD_ID = 30000;

    /**
     * The conjunctions' collection
     */
    private final MongoCollection<Document> conjunctionsCollection;

    /**
     * Constructor
     *
     * @param conjunctionsCollection The conjunctions' collection, cannot be null
     */
    public ChallengeClass(MongoCollection<Document> conjunctionsCollection) {
        Objects.requireNonNull(conjunctionsCollection);
        this.conjunctionsCollection = conjunctionsCollection;
    }

    /**
     * Adjusts the suggestions and collision probabilities of all conjunctions in the DB whose satellites' norad_id are
     * both under the maximum norad ID and whose theta values are problematic
     */
    public synchronized void adjustConjunctionsBasedOnTheta() {
        Bson filter = and(exists(MongoKeys.NEWEST_RISK_ESTIMATION), exists(MongoKeys.NEWEST_RISK_PREDICTION), lt(MongoKeys.SAT1_NORAD_ID,
                MAXIMUM_NORAD_ID), lt(MongoKeys.SAT2_NORAD_ID, MAXIMUM_NORAD_ID));
        for (Document conjunction : conjunctionsCollection.find(filter)) {
            adjustConjunctionBasedOnTheta(conjunction);
        }
    }

    /**
     * Adjusts the suggestion and collision probabilities of the conjunction with the given conjunctionId if its
     * satellites' norad_id are both under the maximum norad ID and whose theta value is not problematic
     *
     * @param conjunctionId The conjunctionId of the conjunction
     */
    public synchronized void adjustConjunctionBasedOnTheta(String conjunctionId) {
        Bson filter = and(exists(MongoKeys.NEWEST_RISK_ESTIMATION), exists(MongoKeys.NEWEST_RISK_PREDICTION), eq(MongoKeys.CONJUNCTION_ID,
                conjunctionId), lt(MongoKeys.SAT1_NORAD_ID, MAXIMUM_NORAD_ID), lt(MongoKeys.SAT2_NORAD_ID, MAXIMUM_NORAD_ID));
        Document conjunction = conjunctionsCollection.find(filter).first();
        if (conjunction != null) {
            adjustConjunctionBasedOnTheta(conjunction);
        } else {
            logger.error("There is no conjunction with Id {}", conjunctionId);
        }
    }


    /**
     * Adjusts the suggestion and collision probabilities of the given conjunction, without checking first if its
     * satellites' norad id are under the maximum allowed values and if all needed keys are present
     *
     * @param conjunction The conjunction
     */
    public synchronized void adjustConjunctionBasedOnTheta(Document conjunction) {
        Document riskPrediction = conjunction.get("newest_risk_prediction", Document.class);
        boolean shouldBeSuggested = handleRiskPrediction(riskPrediction);
        if (!shouldBeSuggested) {
            Document newestRiskEstimation = conjunction.get(MongoKeys.NEWEST_RISK_ESTIMATION, Document.class);
            newestRiskEstimation.put(MongoKeys.SUGGESTED, false);
            updateConjunction(conjunction);
        }
    }

    /**
     * Handles the List of riskTrends: checks whether each of them is valid and if so, analyzes each 2 consecutive
     * trends
     *
     * @param riskTrends the List of risk trends
     * @return a RiskTrendAnalysisResult object
     */
    private RiskTrendAnalysisResult handleRiskTrends(List<Document> riskTrends) {
        RiskTrendAnalysisResult result = new RiskTrendAnalysisResult();
        for (int i = 1; i < riskTrends.size(); i++) {
            Document firstRiskTrend = riskTrends.get(i - 1);
            Document secondRiskTrend = riskTrends.get(i);
            boolean isThetaProblematic;
            if (isTrendValid(firstRiskTrend) && isTrendValid(secondRiskTrend)) {
                isThetaProblematic = handleConsecutiveRiskTrends(firstRiskTrend, secondRiskTrend);
                if (result.isShouldBeSuggested() && isThetaProblematic) {
                    result.setShouldBeSuggested(false);
                }
                if (isThetaProblematic && i == riskTrends.size() - 1) {
                    result.setLatestCollisionProbability(secondRiskTrend.getDouble(MongoKeys.COLLISION_PROBABILITY));
                }
            } else {
                result.setShouldBeSuggested(false);
            }
        }
        return result;
    }

    /**
     * Handles two consecutive risk trends: it calculates theta and, if problematic, adjusts the collision
     * probability of the second risk trend
     *
     * @param firstRiskTrend  the first risk trend
     * @param secondRiskTrend the second risk trend
     * @return true if the resulting theta is problematic
     */
    private boolean handleConsecutiveRiskTrends(Document firstRiskTrend, Document secondRiskTrend) {
        double[] theta = new double[1];
        double[] timeToTca0 = getTimeToTca(firstRiskTrend);
        double[] timeToTca1 = getTimeToTca(firstRiskTrend);
        double[] collisionProbability0 = getCollisionProbability(firstRiskTrend);
        double[] collisionProbability1 = getCollisionProbability(secondRiskTrend);

        Calculations.analyze_theta(timeToTca0, collisionProbability0, timeToTca1, collisionProbability1, theta);
        boolean isThetaProblematic = Calculations.check_theta(theta);

        if (isThetaProblematic) {
            double[] resultAdjustedValue = new double[]{-1d};
            Calculations.adjust_coll_prob(timeToTca0, collisionProbability0, timeToTca1, collisionProbability1,
                    resultAdjustedValue);
            if (resultAdjustedValue[0] != -1d) {
                adjustCollisionProbability(secondRiskTrend, resultAdjustedValue[0]);
            }
        }
        return isThetaProblematic;
    }


    /**
     * Handles all operations concerning the risk prediction
     *
     * @param riskPrediction the riskPrediction document
     * @return false if the risk estimation should not be suggested
     */
    private boolean handleRiskPrediction(Document riskPrediction) {
        RiskTrendAnalysisResult result = new RiskTrendAnalysisResult();
        List<Document> riskTrend = riskPrediction.get(MongoKeys.RISK_TREND, List.class);
        if (areTrendsValid(riskTrend)) {
            result = handleRiskTrends(riskTrend);
            if (areTrendsValid(riskTrend) && result.getLatestCollisionProbability() != null) {
                adjustCollisionProbability(riskPrediction, result.getLatestCollisionProbability());
            }

        }
        return result.isShouldBeSuggested();
    }

    /**
     * Adjusts the collision probability based on the given value
     *
     * @param document            the document that has a key named "collision_probability"
     * @param resultAdjustedValue the collision probability adjusted value
     */
    private void adjustCollisionProbability(Document document, double resultAdjustedValue) {
        document.put(MongoKeys.COLLISION_PROBABILITY, resultAdjustedValue);
    }

    private double[] getTimeToTca(Document trend) {
        return new double[]{((Number) trend.get(MongoKeys.TIME_TO_TCA)).doubleValue()};
    }

    private double[] getCollisionProbability(Document trend) {
        return new double[]{((Number) trend.get(MongoKeys.COLLISION_PROBABILITY)).doubleValue()};
    }

    /**
     * Updates the DB with the given conjunction
     *
     * @param conjunction the conjunction to update
     */
    private void updateConjunction(Document conjunction) {
        conjunctionsCollection.replaceOne(eq(MongoKeys.ID, conjunction.get(MongoKeys.ID)), conjunction);
    }

    /**
     * Verifies whether the given riskTrend List is valid
     *
     * @param riskTrends the riskTrend List
     * @return true if valid
     */
    private boolean areTrendsValid(List<Document> riskTrends) {
        boolean valid = true;
        if (riskTrends.size() < 2) {
            valid = false;
            logger.warn("There are not enough risk trends to analyze theta!");
        }

        return valid;
    }

    /**
     * Verifies whether the given riskTrend document has valid values
     *
     * @param riskTrend the riskTrend document
     * @return true if valid
     */
    private boolean isTrendValid(Document riskTrend) {
        boolean valid = true;
        if (riskTrend.containsKey(MongoKeys.TIME_TO_TCA) && riskTrend.containsKey(MongoKeys.COLLISION_PROBABILITY)) {
            Object timeToTca = riskTrend.get(MongoKeys.TIME_TO_TCA);
            Object collisionProbability = riskTrend.get(MongoKeys.COLLISION_PROBABILITY);

            if (Objects.isNull(timeToTca)) {
                logger.warn("time_to_tca for a risk trend is invalid");
                valid = false;
            }
            if (Objects.isNull(collisionProbability) || ((Double) collisionProbability).isNaN()) {
                logger.warn("collision_probability for a risk trend is invalid");
                valid = false;
            }
        } else {
            logger.warn("A risk trend is invalid");
            valid = false;
        }

        return valid;
    }


    /**
     * Class used as a result for handleRiskTrends
     */
    private static class RiskTrendAnalysisResult {

        /**
         * Whether the newest_risk_estimation should be suggested
         */
        private boolean shouldBeSuggested = true;

        /**
         * The value to be replaced as the collision_probability of newest_risk_prediction
         */
        private Double latestCollisionProbability;

        public boolean isShouldBeSuggested() {
            return shouldBeSuggested;
        }

        public void setShouldBeSuggested(boolean shouldBeSuggested) {
            this.shouldBeSuggested = shouldBeSuggested;
        }

        public Double getLatestCollisionProbability() {
            return latestCollisionProbability;
        }

        public void setLatestCollisionProbability(Double latestCollisionProbability) {
            this.latestCollisionProbability = latestCollisionProbability;
        }
    }

}
