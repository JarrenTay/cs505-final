package cs505pubsubcep;

import cs505pubsubcep.CEP.CEPEngine;
import cs505pubsubcep.CEP.PatientEvent;
import cs505pubsubcep.CEP.LocationNode;
import cs505pubsubcep.CEP.HospitalResponse;
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
import org.neo4j.driver.Record;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionWork;

import static org.neo4j.driver.Values.parameters;

public class Launcher {

    public static final String API_SERVICE_KEY = "12064341"; //Change this to your student id
    public static final int WEB_PORT = 8088;
    public static String inputStreamName = null;
    public static long accessCount = -1;
    public static String zipDetailsFilePath = "/var/lib/neo4j/myapp/kyzipdetails.csv";
    public static String hospitalsFilePath = "/var/lib/neo4j/import/hospitals.csv";
    public static String distanceFilePath = "/var/lib/neo4j/import/kyzipdistance.csv";
    public static Map<String, Integer> zipCodeCases;
    public static Map<String, Integer> zipCodeVisited;
    public static List<String> zipList; 
    public static int positiveCases = 0;
    public static int negativeCases = 0;

    public static TopicConnector topicConnector;

    public static CEPEngine zipEngine = null;
    public static CEPEngine statusEngine = null;
    public static CEPEngine patientEngine = null;

    private static Driver driver;

    public static void main(String[] args) throws IOException {

        System.out.println("Importing Zip Codes");

        driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "cs505pass"));

        resetData();

        zipCodeCases = zipToMap(zipDetailsFilePath);
        zipCodeVisited = zipToMap(zipDetailsFilePath);

        if (!databaseExists()) {
            addZipLocations(zipDetailsFilePath);
            System.out.println("finished adding locations");
            addHospitalNodes(hospitalsFilePath);
            System.out.println("finished adding hospitals");
            addZipDistances(distanceFilePath);
            System.out.println("finished adding distances");
        } else {
            System.out.println("dont add db");
        }


        System.out.println("Starting CEP...");

        //Embedded database initialization

        zipEngine = new CEPEngine();
        statusEngine = new CEPEngine();
        patientEngine = new CEPEngine();

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

        zipEngine.createCEP(inputStreamName, outputStreamName, inputStreamAttributesString, outputStreamAttributesString, rtr1);

        inputStreamName = "PatientInStream";
        inputStreamAttributesString = "first_name string, last_name string, mrn string, zip_code string, patient_status_code string";

        outputStreamName = "StatusAlertStream";
        outputStreamAttributesString = "patient_status_code string, count long";

	//CEP query string for getting positives and negatives
        String rtr2 = " " +
                "from PatientInStream#window.timeBatch(15 sec) " +
                "select patient_status_code, count() as count " +
                "group by patient_status_code " +
                "insert into StatusAlertStream; ";

        statusEngine.createCEP(inputStreamName, outputStreamName, inputStreamAttributesString, outputStreamAttributesString, rtr2);

        inputStreamName = "PatientInStream";
        inputStreamAttributesString = "first_name string, last_name string, mrn string, zip_code string, patient_status_code string";

        outputStreamName = "PatientOutStream";
        outputStreamAttributesString = "mrn string, zip_code string, patient_status_code string";

	//CEP query string for getting new patients
        String of = " " +
                "from PatientInStream " +
                "select mrn, zip_code, patient_status_code " +
                "insert into PatientOutStream; ";

        patientEngine.createCEP(inputStreamName, outputStreamName, inputStreamAttributesString, outputStreamAttributesString, of);

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
        return outMap;
    }

    private static void addZipLocations(String zipDetailsFile) {
        try (Session session = driver.session() ) {
            session.writeTransaction( tx -> {
                tx.run ("LOAD CSV WITH HEADERS FROM 'file:///kyzipdetails.csv' AS line CREATE (:Location {zipCode: line.zip})", parameters());
                return 1;
            } );
        }
    }

    private static void addHospitalNodes(String hospitalsFile) {

        try (Session session = driver.session() ) { 
            session.writeTransaction( tx -> {
                tx.run ("CREATE (:Hospital {hospitalId: \"0\", beds:-1, taken:0, level:\"\", zipCode:\"0\"})", parameters());
                return 1;
            } );
        }

        try (Session session = driver.session() ) { 
            session.writeTransaction( tx -> {
                tx.run ("LOAD CSV WITH HEADERS FROM 'file:///hospitals.csv' AS line MATCH (a:Location) WHERE a.zipCode = line.zip CREATE (b:Hospital {hospitalId: line.hospitalId, beds: toInteger(line.beds), taken: 0, level: line.trauma, zipCode: line.zip}),(a)-[:CONTAINS]->(b)", parameters());
                return 1;
            } );
        }
    }

    private static void addZipDistances(String distanceFile) {
        Path pathToFile = Paths.get(distanceFile);
        try (BufferedReader br = Files.newBufferedReader(pathToFile, StandardCharsets.US_ASCII)) {
            String line = br.readLine();
            line = br.readLine();
            int count = 0;
            while (line != null) {
                String[] attributes = line.split(",");
                if (Double.parseDouble(attributes[2]) < 25.0) {
                    String queryString = "MATCH (a:Location),(b:Location) WHERE a.zipCode = '" + attributes[0] + "' AND b.zipCode = '" + attributes[1] + "' CREATE(a)-[:CONNECTED_TO {distance: " + attributes[2] + "}]->(b)";
                    try (Session session = driver.session() ) {
                        session.writeTransaction( tx -> {
                            tx.run (queryString, parameters());
                            return 1;
                        } );
                    }
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

    public static String checkLocation(String locationZip, String patientStatusCode) {
        String hid = "";
        int beds = 0;
        int taken = 0;
        String level = "";
        String out = "";
        try (Session session = driver.session()) {
            out = session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (a:Location)-[r:CONTAINS]-(b:Hospital) WHERE a.zipCode=$zip RETURN b.hospitalId, b.beds, b.taken, b.level", parameters("zip", locationZip));
                while (result.hasNext() ) {
                    Record jo = result.next();
                    if (jo.get("b.beds").asInt() != jo.get("b.taken").asInt()) {
                        if ((patientStatusCode.equals("6") && !jo.get("b.level").asString().equals("NOT AVAILABLE")) || !patientStatusCode.equals("6")) {
                            return(jo.get("b.hospitalId").asString());
                        }
                    }

                }
                return "";
            });
        }
        return out; 
    }

    public static void createPatientNode(String hospital, String mrn) {

        try (Session session = driver.session() ) {
            session.writeTransaction( tx -> {
                tx.run ("MATCH (a:Hospital) WHERE a.hospitalId = $hid SET a.taken = a.taken + 1 CREATE (b:Patient {mrn: $mrn }), (a)-[r:SERVES]->(b)", parameters("hid", hospital, "mrn", mrn));
                return 1;
            });
        } 

    }

    public static void addToQueue(PriorityQueue<LocationNode> queue, Double distance, String zip) {
        try (Session session = driver.session()) {
            session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (a:Location)-[r:CONNECTED_TO]-(b:Location) WHERE a.zipCode=$zip RETURN b.zipCode, r.distance ORDER BY r.distance LIMIT 5", parameters("zip", zip));
                while (result.hasNext() ) {
                    Record jo = result.next();
                    String newZip = jo.get("b.zipCode").asString();
                    if (zipCodeVisited.get(newZip) != 1) {
                        zipCodeVisited.put(newZip, 1);
                        queue.add(new LocationNode(newZip, distance + jo.get("r.distance").asDouble()));
                    }
                }
                return 1;
            });
        }           
    }

    public static void createPatient(PatientEvent newPatient) {

        if (newPatient.patient_status_code.equals("0") || newPatient.patient_status_code.equals("1") || newPatient.patient_status_code.equals("2") || newPatient.patient_status_code.equals("4")) {

            try (Session session = driver.session() ) {
                session.writeTransaction( tx -> {
                    tx.run ("MATCH (a:Hospital) WHERE a.hospitalId = \"0\" CREATE (b:Patient {mrn: $mrn }), (a)-[r:SERVES]->(b)", parameters("mrn", newPatient.mrn));
                    return 1;
                });
            }           

            System.out.println("Put new patient with trauma " + newPatient.patient_status_code + " at 0");
        } else {
            for (Map.Entry<String, Integer> entry : zipCodeVisited.entrySet()) {
                zipCodeVisited.put(entry.getKey(), 0);
            }

            PriorityQueue<LocationNode> queue = new PriorityQueue<LocationNode>();
            queue.add(new LocationNode(newPatient.zip_code, 0.0));
            zipCodeVisited.put(newPatient.zip_code, 1);
            LocationNode currNode = new LocationNode("", 0.0);
            boolean nodeAdded = false;
            String goodHospital = "";

            while (queue.size() != 0) {
                currNode = queue.poll();
                goodHospital = checkLocation(currNode.zip, newPatient.patient_status_code);
                if (goodHospital != "") {
                    createPatientNode(goodHospital, newPatient.mrn);
                    nodeAdded = true;
                    break;
                }
                addToQueue(queue, currNode.distance, currNode.zip);
            }
            if (nodeAdded == true) {
                System.out.println("Put new patient with trauma " + newPatient.patient_status_code + " at " + currNode.zip);
            } else {
                System.out.println("COULD NOT PLACE PATIENT WITH TRAUMA" + newPatient.patient_status_code + " from " + newPatient.zip_code);
            }
        }
    }

    public static PatientEvent getPatient(String mrn) {
        PatientEvent patient;
        try (Session session = driver.session()) {
            patient = session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (a:Hospital)-[SERVES]-(b:Patient) WHERE b.mrn=$mrn RETURN a.hospitalId", parameters("mrn", mrn));
                PatientEvent outPatient = new PatientEvent();
                outPatient.mrn = mrn;
                outPatient.zip_code = "-1";
                while (result.hasNext() ) {
                    Record jo = result.next();
                    outPatient.zip_code = jo.get("a.hospitalId").asString();
                }
                return outPatient;
            });
        }
        return patient;
    }

    public static HospitalResponse getHospital(String hid) {
        HospitalResponse hr;
        try (Session session = driver.session()) {
            hr = session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (a:Hospital) WHERE a.hospitalId=$hid RETURN a.beds, a.taken, a.zipCode", parameters("hid", hid));
                HospitalResponse outHr = new HospitalResponse();

                while (result.hasNext() ) {
                    Record jo = result.next();
                    outHr.totalBeds = Integer.toString(jo.get("a.beds").asInt());
                    int availableBeds = Integer.parseInt(outHr.totalBeds) - jo.get("a.taken").asInt();
                    outHr.avalableBeds = Integer.toString(availableBeds);
                    outHr.zipCode = jo.get("a.zipCode").asString();
                }
                return outHr;
            });
        }
        return hr;
    }

    public static boolean databaseExists() {
        boolean out = false;
        try (Session session = driver.session()) {
            out = session.readTransaction( tx -> {
                Result result = tx.run ("MATCH (n) RETURN n");
                while (result.hasNext() ) {
                    return true;
                }
                return false;
            });
        }
        return out; 
    }

    public static void resetData() {

        zipCodeCases = zipToMap(zipDetailsFilePath);
        zipCodeVisited = zipToMap(zipDetailsFilePath);
        accessCount = -1;
        if (zipList != null) {
            zipList.removeAll(zipList); 
        }
        positiveCases = 0;
        negativeCases = 0;

        try (Session session = driver.session() ) {
            session.writeTransaction( tx -> {
                tx.run ("MATCH (n:Patient) DETACH DELETE n");
                return 1;
            } );
        }
        try (Session session = driver.session() ) {
            session.writeTransaction( tx -> {
                tx.run ("MATCH (n:Hospital) SET n.taken = 0");
                return 1;
            } );
        }
    }

}
