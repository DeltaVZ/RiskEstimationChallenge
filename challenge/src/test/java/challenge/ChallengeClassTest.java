package challenge;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Filters.lt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.powermock.api.mockito.PowerMockito.*;
import static challenge.ChallengeClass.MAXIMUM_NORAD_ID;

import challenge.ChallengeClass;
import challenge.utils.MongoIntegrationUtils;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.testng.PowerMockTestCase;
import org.testng.annotations.*;
import utils.Calculations;
import utils.MongoCollectionNames;
import utils.MongoKeys;

import java.util.*;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

@PrepareForTest(Calculations.class)
public class ChallengeClassTest extends PowerMockTestCase {

    private final List<Document> conjunctions = new ArrayList<>();
    private MongoCollection<Document> conjunctionsCollection;
    private final double mockThetaValue = 2;
    private final double mockCollisionProbabilityValue = 0.5;
    private final MongoIntegrationUtils mongoIntegrationUtils = new MongoIntegrationUtils();
    private MongoClient mongoClient;

    @BeforeClass
    public void setUp() {
        mongoIntegrationUtils.startMongoContainer();
        mongoClient = mongoIntegrationUtils.createMongoClient();
        this.conjunctionsCollection = mongoIntegrationUtils.getMongoDatabase(mongoClient).getCollection(MongoCollectionNames.CONJUNCTIONS);
    }

    @AfterMethod
    public void cleanUp() {
        conjunctionsCollection.deleteMany(in(MongoKeys.CONJUNCTION_ID,
                conjunctions.stream().map(i -> i.get(MongoKeys.CONJUNCTION_ID)).collect(Collectors.toList())));
        conjunctions.clear();
    }

    @AfterClass
    public void tearDown() {
        if(mongoClient != null) {
            mongoClient.close();
        }
        mongoIntegrationUtils.mongoContainer.stop();
    }

    @Test
    public void testAdjustConjunctionsBasedOnTheta() throws Exception {

        // Mock the calculations class and all of its native static methods
        mockCalculations(true, false, true, false, false, false);

        // Add random conjunctions to the DB
        addRandomConjunctions(2);

        // Spy the MongoCollection of conjunctions just to capture the used filter
        MongoCollection<Document> conjunctionCollection = spy(conjunctionsCollection);
        ArgumentCaptor<Bson> argumentCaptor = ArgumentCaptor.forClass(Bson.class);
        doCallRealMethod().when(conjunctionCollection).find(argumentCaptor.capture());

        // Create the ChallengeClass and call adjustConjunctionBasedOnTheta(String conjunctionId) for each conjunction
        ChallengeClass challengeClass = new ChallengeClass(conjunctionCollection);
        for (Document conjunction : conjunctions) {
            challengeClass.adjustConjunctionBasedOnTheta(conjunction.getString(MongoKeys.CONJUNCTION_ID));
        }

        Document problematicThetaConjunction = conjunctionsCollection.find(eq(MongoKeys.CONJUNCTION_ID,
                conjunctions.get(0).getString(MongoKeys.CONJUNCTION_ID))).first();
        Document goodThetaConjunction = conjunctionsCollection.find(eq(MongoKeys.CONJUNCTION_ID,
                conjunctions.get(1).getString(MongoKeys.CONJUNCTION_ID))).first();

        // Check that the native methods have been invocated the right number of times
        // There are 4 risk trends per conjunction, which are mocked in the following way:
        // For the first conjunction:
        // 1st couple does not have a problematic theta
        // 2nd couple does have problematic theta
        // 3rd couple does not have problematic theta
        // For the second conjunction no couple has problematic thetas

        PowerMockito.verifyStatic(Calculations.class, times(6));
        Calculations.check_theta(any());

        PowerMockito.verifyStatic(Calculations.class, times(6));
        Calculations.analyze_theta(any(), any(), any(), any(), any());

        PowerMockito.verifyStatic(Calculations.class, times(2));
        Calculations.adjust_coll_prob(any(), any(), any(), any(), any());

        // The first conjunction should have had its status changed, as it had problematic theta values
        assertNotEquals(problematicThetaConjunction, conjunctions.get(0));
        assertFalse(((Document) problematicThetaConjunction.get(MongoKeys.NEWEST_RISK_ESTIMATION)).getBoolean(MongoKeys.SUGGESTED));
        List<Document> riskTrendArray = (List<Document>) ((Document) problematicThetaConjunction.get(
                MongoKeys.NEWEST_RISK_PREDICTION)).get(MongoKeys.RISK_TREND, List.class);
        for (int i = 1; i < riskTrendArray.size(); i++) {
            if (i != 2) {
                assertEquals(riskTrendArray.get(i).get(MongoKeys.COLLISION_PROBABILITY), this.mockCollisionProbabilityValue);
            } else {
                assertNotEquals(riskTrendArray.get(i).get(MongoKeys.COLLISION_PROBABILITY), this.mockCollisionProbabilityValue);
            }
        }

        assertEquals(((Document) problematicThetaConjunction.get(MongoKeys.NEWEST_RISK_PREDICTION)).get(
                MongoKeys.COLLISION_PROBABILITY), this.mockCollisionProbabilityValue);

        // The second conjunction was not changed because its theta was not problematic
        assertEquals(goodThetaConjunction, conjunctions.get(1));

        // Check whether the filters are as expected
        List<Bson> usedFilters = conjunctions.stream().map(i -> and(exists(MongoKeys.NEWEST_RISK_ESTIMATION), exists(
                        MongoKeys.NEWEST_RISK_PREDICTION),
                eq(MongoKeys.CONJUNCTION_ID, i.get(MongoKeys.CONJUNCTION_ID)), lt(MongoKeys.SAT1_NORAD_ID, MAXIMUM_NORAD_ID), lt(
                        MongoKeys.SAT2_NORAD_ID,
                        MAXIMUM_NORAD_ID))).toList();
        assertEquals(argumentCaptor.getAllValues(), usedFilters);
    }

    @Test
    public void testAdjustConjunctionBasedOnTheta() {

        // Create the argument captor for capturing the filter used by the MongoCollection
        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);

        // Spy the ChallengeClass
        ChallengeClass challengeClass = spyChallengeClass(captor);

        // Call adjustConjunctionsBasedOnTheta() and verify that adjustConjunctionBasedOnTheta(Document conjunction)
        // has been called the right number of times
        challengeClass.adjustConjunctionsBasedOnTheta();
        verify(challengeClass, times(2)).adjustConjunctionBasedOnTheta(any(Document.class));

        // Verify that the filter used by the MongoCollection is as expected
        assertEquals(captor.getValue().toBsonDocument(), and(exists(MongoKeys.NEWEST_RISK_ESTIMATION), exists(
                MongoKeys.NEWEST_RISK_PREDICTION), lt(MongoKeys.SAT1_NORAD_ID,
                MAXIMUM_NORAD_ID), lt(MongoKeys.SAT2_NORAD_ID, MAXIMUM_NORAD_ID)).toBsonDocument());
    }

    private void addRandomConjunctions(int conjunctionsNumber) {
        Document randomConjunction;
        for (int i = 1; i <= conjunctionsNumber; i++) {
            randomConjunction = getRandomConjunction();
            this.conjunctions.add(randomConjunction);
            conjunctionsCollection.insertOne(randomConjunction);
        }
    }

    private void mockCalculations(Boolean t, Boolean... ts) throws Exception {
        PowerMockito.mockStatic(Calculations.class);
        when(Calculations.check_theta(ArgumentMatchers.eq(new double[]{mockThetaValue}))).thenReturn(t, ts);
        PowerMockito.doAnswer(i -> ((double[]) i.getArguments()[4])[0] = mockThetaValue).when(Calculations.class,
                "analyze_theta", any(), any(), any(),
                any(), any());
        PowerMockito.doAnswer(i -> ((double[]) i.getArguments()[4])[0] = mockCollisionProbabilityValue).when(Calculations.class,
                "adjust_coll_prob", any(), any(), any(), any(), any());


    }

    private ChallengeClass spyChallengeClass(ArgumentCaptor<Bson> captor) {
        MongoCollection<Document> mongoCollectionMock = mock(MongoCollection.class);
        FindIterable<Document> findIterableMock = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterableMock.cursor()).thenReturn(cursor);
        when(findIterableMock.iterator()).thenReturn(cursor);
        when(cursor.hasNext())
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false);
        when(cursor.next())
                .thenReturn(new Document())
                .thenReturn(new Document());
        Mockito.when(mongoCollectionMock.find(captor.capture())).thenReturn(findIterableMock);
        ChallengeClass challengeClass = new ChallengeClass(mongoCollectionMock);
        ChallengeClass challengeClassSpy = spy(challengeClass);
        doNothing().when(challengeClassSpy).adjustConjunctionBasedOnTheta(any(Document.class));
        return challengeClassSpy;
    }

    // Can be replaced by a Conjunction Model builder
    private Document getRandomConjunction() {
        return Document.parse("""
                {
                  "conjunction_id": "random_uuid"
                  "sat1_norad_id": 0,
                  "sat2_norad_id": 0,
                  "sat1_name": "test_name1",
                  "sat2_name": "test_name2",
                  "criticality": "non_critical",
                  "criticality_source": "risk_estimation",
                  "newest_risk_estimation": {
                    "risk_estimation_id": "550e8400-e29b-11d4-a716-446655440000",
                    "creation_date": "2016-07-21T00:31:50.000Z",
                    "originator": "COMPANY:Orbits GmbH",
                    "tca": "2016-07-21T00:31:50.000Z",
                    "collision_probability": 1,
                    "collision_probability_method": "Foster-1992",
                    "miss_distance": 0.135,
                    "relative_speed": 13.435,
                    "sat1_hard_body_radius": 0.015,
                    "sat2_hard_body_radius": 0.5,
                    "r": 0.015,
                    "t": -0.05,
                    "n": 0.154,
                    "r_dot": 0.390367,
                    "t_dot": 5.741902,
                    "n_dot": -7.39698,
                    "sat1_covariance_r_scaling_factor": 1.25,
                    "sat1_covariance_t_scaling_factor": 1.25,
                    "sat1_covariance_n_scaling_factor": 1.25,
                    "sat2_covariance_r_scaling_factor": 1.25,
                    "sat2_covariance_t_scaling_factor": 1.25,
                    "sat2_covariance_n_scaling_factor": 1.25,
                    "sat1_state_at_tca": {
                      "OPM_HEADER": {
                        "CCSDS_OPM_VERS": 2,
                        "CREATION_DATE": "07-02-2019T08:47:13.1345",
                        "ORIGINATOR": "COMPANY:Orbits GmbH"
                      },
                      "OPM_META_DATA": {
                        "OBJECT_NAME": "COMPANYSat-1",
                        "OBJECT_ID": "2020-000-A",
                        "CENTER_NAME": "EARTH",
                        "REF_FRAME": "GCRF",
                        "REF_FRAME_EPOCH": "2000-01-01T00:00:00Z",
                        "TIME_SYSTEM": "UTC",
                        "COMMENTS": [
                          "This is a test satellite"
                        ]
                      },
                      "OPM_DATA": {
                        "EPOCH": "2019-02-13T21:34:59.341Z",
                        "X": 704.71923,
                        "Y": -4698.489732,
                        "Z": -5332.327296,
                        "X_DOT": -0.15347163,
                        "Y_DOT": 5.60157079,
                        "Z_DOT": -4.91280009,
                        "MASS": 1.3,
                        "SOLAR_RAD_COEFF": 1.3,
                        "DRAG_AREA": 0.01,
                        "DRAG_COEFF": 2.2,
                        "COV_REF_FRAME": "GCRF",
                        "CX_X": 0,
                        "CY_X": 0,
                        "CY_Y": 0,
                        "CZ_X": 0,
                        "CZ_Y": 0,
                        "CZ_Z": 0,
                        "CX_DOT_X": 0,
                        "CX_DOT_Y": 0,
                        "CX_DOT_Z": 0,
                        "CX_DOT_X_DOT": 0,
                        "CY_DOT_X": 0,
                        "CY_DOT_Y": 0,
                        "CY_DOT_Z": 0,
                        "CY_DOT_X_DOT": 0,
                        "CY_DOT_Y_DOT": 0,
                        "CZ_DOT_X": 0,
                        "CZ_DOT_Y": 0,
                        "CZ_DOT_Z": 0,
                        "CZ_DOT_X_DOT": 0,
                        "CZ_DOT_Y_DOT": 0,
                        "CZ_DOT_Z_DOT": 0,
                        "MANEUVERS": [
                          {
                            "MAN_EPOCH_IGNITION": "2019-03-22T12:00:00.000Z",
                            "MAN_DURATION": 3600,
                            "MAN_REF_FRAME": "RTN",
                            "USER_DEFINED_MAN_A_1": 9.1e-9,
                            "USER_DEFINED_MAN_A_2": 1.1e-8,
                            "USER_DEFINED_MAN_A_3": 9.1e-9
                          }
                        ],
                        "USER_DEFINED_THRUST_UNCERTAINTY": 0.01,
                        "USER_DEFINED_THRUST_POINTING_UNCERTAINTY": 120,
                        "USER_DEFINED_BC": 0,
                        "USER_DEFINED_RESIDUAL_1": 0,
                        "USER_DEFINED_RESIDUAL_2": 0,
                        "USER_DEFINED_RESIDUAL_3": 0,
                        "USER_DEFINED_RESIDUAL_4": 0,
                        "USER_DEFINED_RESIDUAL_5": 0,
                        "USER_DEFINED_RESIDUAL_6": 0,
                        "COMMENTS": [
                          "ORBIT DETERMINATION SCHEME = WLS",
                          "ORBIT DETERMINATION RMS = 0.765"
                        ],
                        "USER_DEFINED_IN_SUN": true
                      }
                    },
                    "sat2_state_at_tca": {
                      "OPM_HEADER": {
                        "CCSDS_OPM_VERS": 2,
                        "CREATION_DATE": "07-02-2019T08:47:13.1345",
                        "ORIGINATOR": "COMPANY:Orbits GmbH"
                      },
                      "OPM_META_DATA": {
                        "OBJECT_NAME": "COMPANYSat-1",
                        "OBJECT_ID": "2020-000-A",
                        "CENTER_NAME": "EARTH",
                        "REF_FRAME": "GCRF",
                        "REF_FRAME_EPOCH": "2000-01-01T00:00:00Z",
                        "TIME_SYSTEM": "UTC",
                        "COMMENTS": [
                          "This is a test satellite"
                        ]
                      },
                      "OPM_DATA": {
                        "EPOCH": "2019-02-13T21:34:59.341Z",
                        "X": 704.71923,
                        "Y": -4698.489732,
                        "Z": -5332.327296,
                        "X_DOT": -0.15347163,
                        "Y_DOT": 5.60157079,
                        "Z_DOT": -4.91280009,
                        "MASS": 1.3,
                        "SOLAR_RAD_COEFF": 1.3,
                        "DRAG_AREA": 0.01,
                        "DRAG_COEFF": 2.2,
                        "COV_REF_FRAME": "GCRF",
                        "CX_X": 0,
                        "CY_X": 0,
                        "CY_Y": 0,
                        "CZ_X": 0,
                        "CZ_Y": 0,
                        "CZ_Z": 0,
                        "CX_DOT_X": 0,
                        "CX_DOT_Y": 0,
                        "CX_DOT_Z": 0,
                        "CX_DOT_X_DOT": 0,
                        "CY_DOT_X": 0,
                        "CY_DOT_Y": 0,
                        "CY_DOT_Z": 0,
                        "CY_DOT_X_DOT": 0,
                        "CY_DOT_Y_DOT": 0,
                        "CZ_DOT_X": 0,
                        "CZ_DOT_Y": 0,
                        "CZ_DOT_Z": 0,
                        "CZ_DOT_X_DOT": 0,
                        "CZ_DOT_Y_DOT": 0,
                        "CZ_DOT_Z_DOT": 0,
                        "MANEUVERS": [
                          {
                            "MAN_EPOCH_IGNITION": "2019-03-22T12:00:00.000Z",
                            "MAN_DURATION": 3600,
                            "MAN_REF_FRAME": "RTN",
                            "USER_DEFINED_MAN_A_1": 9.1e-9,
                            "USER_DEFINED_MAN_A_2": 1.1e-8,
                            "USER_DEFINED_MAN_A_3": 9.1e-9
                          }
                        ],
                        "USER_DEFINED_THRUST_UNCERTAINTY": 0.01,
                        "USER_DEFINED_THRUST_POINTING_UNCERTAINTY": 120,
                        "USER_DEFINED_BC": 0,
                        "USER_DEFINED_RESIDUAL_1": 0,
                        "USER_DEFINED_RESIDUAL_2": 0,
                        "USER_DEFINED_RESIDUAL_3": 0,
                        "USER_DEFINED_RESIDUAL_4": 0,
                        "USER_DEFINED_RESIDUAL_5": 0,
                        "USER_DEFINED_RESIDUAL_6": 0,
                        "COMMENTS": [
                          "ORBIT DETERMINATION SCHEME = WLS",
                          "ORBIT DETERMINATION RMS = 0.765"
                        ],
                        "USER_DEFINED_IN_SUN": true
                      }
                    },
                    "criticality": "non_critical",
                    "suggested": true,
                    "comment": "string",
                    "based_on": "GCRF"
                  },
                  "newest_risk_prediction": {
                    "risk_prediction_id": "550e8400-e29b-11d4-a716-446655440000",
                    "creation_date": "2019-12-05T00:31:50.000Z",
                    "originator": "COMPANY:Orbits GmbH",
                    "tca": "07-02-2019T08:47:13.1345",
                    "collision_probability": 0.00001,
                    "collision_probability_method": "Foster-1992",
                    "approx_peak_risk": 0.00001,
                    "time_of_peak_risk": "2019-12-05T00:31:50.000Z",
                    "risk_trend": [
                        {
                          "time_to_tca": 7200,
                          "collision_probability": 0.00001
                        },
                        {
                          "time_to_tca": 3600,
                          "collision_probability": 0.000009
                        },
                        {
                          "time_to_tca": 1800,
                          "collision_probability": 0.000008
                        },
                        {
                          "time_to_tca": 900,
                          "collision_probability": 0.000007
                        }
                    ],
                    "miss_distance": 0.135,
                    "relative_speed": 13.435,
                    "r": 0.015,
                    "t": -0.05,
                    "n": 0.154,
                    "r_dot": 0.390367,
                    "t_dot": 5.741902,
                    "n_dot": -7.39698,
                    "sat1_covariance_r_scaling_factor": 0.5,
                    "sat1_covariance_t_scaling_factor": 2,
                    "sat1_covariance_n_scaling_factor": 0.95,
                    "sat2_covariance_r_scaling_factor": 0.5,
                    "sat2_covariance_t_scaling_factor": 2.5,
                    "sat2_covariance_n_scaling_factor": 0.95,
                    "criticality": "non_critical",
                    "suggested": true,
                    "comment": "string",
                    "based_on": "GCRF"
                  }
                }""".replace("random_uuid", UUID.randomUUID().toString()));
    }
}