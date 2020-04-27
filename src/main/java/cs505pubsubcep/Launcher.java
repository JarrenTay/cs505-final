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
    public static Map<String, Integer> zipCodeCases;
    public static List<String> zipList;
    public static int positiveCases = 0;
    public static int negativeCases = 0;

    public static TopicConnector topicConnector;

    public static CEPEngine zipEngine = null;
    public static CEPEngine statusEngine = null;

    private static Driver driver;

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
}

/*
public class CEPQuery {
    public static List<Map<String, Map<String, String>>> zipList;
}*/
