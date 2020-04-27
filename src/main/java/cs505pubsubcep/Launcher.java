package cs505pubsubcep;

import cs505pubsubcep.CEP.CEPEngine;
import cs505pubsubcep.Topics.TopicConnector;
import cs505pubsubcep.httpfilters.AuthenticationFilter;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import javax.ws.rs.core.UriBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.util.*;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionWork;

import static org.neo4j.driver.Values.parameters;

public class Launcher {

    public static final String API_SERVICE_KEY = "12064341"; //Change this to your student id
    public static final int WEB_PORT = 8088;
    public static String inputStreamName = null;
    public static long accessCount = -1;
    public static String zipDetailsFilePath = "/home/jta255/finalProject/cs505-pubsub-cep-template/data/kyzipdetails.csv";
    public static String hospitalsFilePath = "/home/jta255/finalProject/cs505-pubsub-cep-template/data/hospitals.csv";
    public static String distanceFilePath = "/home/jta255/finalProject/cs505-pubsub-cep-template/data/kyzipdistance.csv";
    public static Map<String, Integer> zipCodeCases;
    public static List<String> zipList;
    public static int positiveCases = 0;
    public static int negativeCases = 0;

    public static TopicConnector topicConnector;

    public static CEPEngine zipEngine = null;
    public static CEPEngine statusEngine = null;

    private static Driver driver;
    //private static DatabaseManagementService managementService;
    //public static GraphDatabaseService graphDb;

    public static void main(String[] args) throws IOException {

        System.out.println("Importing Zip Codes");

        zipCodeCases = zipToMap(zipDetailsFilePath);
        zipList = new ArrayList<String> ();

        System.out.println("Starting CEP...");
        //Embedded database initialization

        zipEngine = new CEPEngine();
        statusEngine = new CEPEngine();

        //START MODIFY
        inputStreamName = "PatientInStream";
        String inputStreamAttributesString = "first_name string, last_name string, mrn string, zip_code string, patient_status_code string";

        String outputStreamName = "ZipAlertStream";
        String outputStreamAttributesString = "zip_code string, count long";

	//CEP query string for alerting if there is a double growth per zipcode
        String rtr1 = " " +
                "from PatientInStream#window.timeBatch(15 sec) " +
                "select zip_code, count() as count " +
                "group by zip_code " +
                "insert into ZipAlertStream; ";

        //END MODIFY

        zipEngine.createCEP(inputStreamName, outputStreamName, inputStreamAttributesString, outputStreamAttributesString, rtr1);

        //START MODIFY
        inputStreamName = "PatientInStream";
        inputStreamAttributesString = "first_name string, last_name string, mrn string, zip_code string, patient_status_code string";

        outputStreamName = "StatusAlertStream";
        outputStreamAttributesString = "patient_status_code string, count long";

	//CEP query string for alerting if there is a double growth per zipcode
        String rtr2 = " " +
                "from PatientInStream#window.timeBatch(15 sec) " +
                "select patient_status_code, count() as count " +
                "group by patient_status_code " +
                "insert into StatusAlertStream; ";

        //END MODIFY

        statusEngine.createCEP(inputStreamName, outputStreamName, inputStreamAttributesString, outputStreamAttributesString, rtr2);

        driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "cs505pass"));
/*
        try ( Session session = driver.session() )
        {
            String greeting = session.writeTransaction( new TransactionWork<String>()
            {
                @Override
                public String execute( Transaction tx )
                {
                    Result result = tx.run( "CREATE (a:Greeting) " +
                                                     "SET a.message = $message " +
                                                     "RETURN a.message + ', from node ' + id(a)",
                            parameters( "message", "Test Message" ) );
                    return result.single().get( 0 ).asString();
                }
            } );
            System.out.println( greeting );
        }
*/
/*
        try (Session session = driver.session() ) {
            session.writeTransaction( tx -> {
                tx.run ("MATCH (n) DETACH DELETE n");
                return 1;
            } );
        }

        addHospitalNodes(hospitalsFilePath, driver);
        addHospitalRelationships(distanceFilePath, driver);
*/
        try (Session session = driver.session()) {
            session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (a:Hospital) RETURN a.zip ORDER BY a.zip");
                while (result.hasNext() ) {
                    System.out.println( Integer.toString(result.next().get(0).asInt()));
                }
                return 1;
            });
        }

/*
        managementService = new DatabaseManagementServiceBuilder("/home/jta255/finalProject/hospitalDb");
        graphDb = managementService.database("HospitalDb");
        registerShutdownHook(managementService);
//        var connectionType = RelationshipType.withName("CONNECTS");
        var startNode;
        var endNode;
        var newNode;
        var relationship;
        var newRelationship;
        try (var transaction = database.beginTx()) {
            startNode = transaction.createNode("Hospital");
            startNode.setProperty("hospitalId", 11740202);
            startNode.setProperty("beds", 404);
            startNode.setProperty("zip", 40202);
            endNode = transaction.createNode("Hospital");
            endNode.setProperty("hospitalId", 11640536);
            endNode.setProperty("beds", 604);
            endNode.setProperty("zip", 40536);
            relationship = startNode.createRelationshipTo(endNode, RelTypes.CONNECTED);
            relationship.setProperty("distance", 55.46403169);
            transaction.commit();
        }
        try (var transaction = database.beginTx()) {
            newNode = transaction.findNode("Hospital", "hospitalId", 11740202);
            transaction.commit();
        }
        System.out.println("TEST");
        System.out.println(Integer.toString(newNode.getProperty("beds")));
*/

        System.out.println("CEP Started...");


        //starting Collector
        topicConnector = new TopicConnector();
        topicConnector.connect();

        //Embedded HTTP initialization
        startServer();


        try {
            while (true) {
                Thread.sleep(5000);
            }
        }catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void startServer() throws IOException {

        final ResourceConfig rc = new ResourceConfig()
        .packages("cs505pubsubcep.httpcontrollers")
        .register(AuthenticationFilter.class);

        System.out.println("Starting Web Server...");
        URI BASE_URI = UriBuilder.fromUri("http://0.0.0.0/").port(WEB_PORT).build();
        HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(BASE_URI, rc);

        try {
            httpServer.start();
            System.out.println("Web Server Started...");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static HashMap< String, Integer> zipToMap(String zipDetailsFile) {
        Path pathToFile = Paths.get(zipDetailsFile);
        HashMap< String, Integer> outMap = new HashMap< String, Integer>();
        try (BufferedReader br = Files.newBufferedReader(pathToFile, StandardCharsets.US_ASCII)) {
            String line = br.readLine();
            while (line != null) {
                String[] attributes = line.split(",");
                outMap.put(attributes[0], new Integer(0));
                line = br.readLine();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        //System.out.println(outMap.toString());
        return outMap;
    }

    private static void addHospitalNodes(String hospitalsFile, Driver driver) {
        Path pathToFile = Paths.get(hospitalsFile);
        try (BufferedReader br = Files.newBufferedReader(pathToFile, StandardCharsets.UTF_8)) {
            String line = br.readLine();
            line = br.readLine();
            int count = 0;
            while (line != null) {
                String[] attributes = line.split(",");
                System.out.println(attributes[0]);
                try (Session session = driver.session() ) { 
                    session.writeTransaction( tx -> {
                        tx.run ("CREATE (a:Hospital {hospitalId: $hospitalId, zip: $zip, beds: $beds, level: $level})", parameters("hospitalId", new Integer(attributes[0]), "beds", new Integer(attributes[7]), "zip", new Integer(attributes[5]), "level", attributes[16]));
                        return 1;
                    } );
                }
                System.out.println(Integer.toString(count));
                count = count + 1;
                line = br.readLine();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private static void addHospitalRelationships(String distanceFile, Driver driver) {
        Path pathToFile = Paths.get(distanceFile);
        try (BufferedReader br = Files.newBufferedReader(pathToFile, StandardCharsets.US_ASCII)) {
            String line = br.readLine();
            line = br.readLine();
            int count = 0;
            while (line != null) {
                String[] attributes = line.split(",");
                try (Session session = driver.session() ) {
                    session.writeTransaction( tx -> {
                        tx.run ("MATCH (a:Hospital),(b:Hospital) WHERE a.zip = $aZip AND b.zip = $bZip CREATE(a)-[r:CONNECTED_TO {distance: $distance}]->(b)", parameters("aZip", attributes[0], "bZip", attributes[1], "distance", attributes[2]));
                        return 1;
                    } );
                }
                try (Session session = driver.session() ) {
                    session.writeTransaction( tx -> {
                        tx.run ("MATCH (a:Hospital),(b:Hospital) WHERE a.zip = $aZip AND b.zip = $bZip CREATE(a)-[r:CONNECTED_TO {distance: $distance}]->(b)", parameters("aZip", new Integer(attributes[1]), "bZip", new Integer(attributes[0]), "distance", new Float(attributes[2])));
                        return 1;
                    } );
                }
                if (count % 100 == 0) {
                    System.out.println(Integer.toString(count));
                }
                count = count + 1;
                line = br.readLine();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}
/*
public class CEPQuery {
    public static List<Map<String, Map<String, String>>> zipList;
}*/
