package challenge.utils;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.UuidRepresentation;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Class to be used by integration tests that make use of MongoDB
 */
public class MongoIntegrationUtils {
    public static final String DB_NAME = "myDb";
    public final MongoDBContainer mongoContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    public void startMongoContainer() {
        if(!mongoContainer.isRunning()) {
            mongoContainer.start();
        }
    }

    public MongoClient createMongoClient() {
        return MongoClients.create(MongoClientSettings.builder().uuidRepresentation(UuidRepresentation.STANDARD).applyConnectionString(new ConnectionString(mongoContainer.getConnectionString())).build());
    }

    public MongoDatabase getMongoDatabase(MongoClient mongoClient) {
        return mongoClient.getDatabase(DB_NAME);
    }
}
