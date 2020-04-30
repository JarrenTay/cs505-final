package cs505pubsubcep.CEP;

import io.siddhi.core.util.transport.InMemoryBroker;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.lang.Integer;
import cs505pubsubcep.Launcher;
//import cs505pubsubcep.CEP.CEPQuery;

public class OutputSubscriber implements InMemoryBroker.Subscriber {

    private String topic;
    private String streamName;

    public OutputSubscriber(String topic, String streamName) {
        this.topic = topic;
        this.streamName = streamName;
    }

    @Override
    public void onMessage(Object msg) {

        try {
            System.out.println("OUTPUT CEP EVENT: " + msg);
            System.out.println("");
            if (this.streamName == "ZipAlertStream") {
                Gson gson = new Gson();
                ZipQuery[] eventList = gson.fromJson(msg.toString(), ZipQuery[].class); 
       
                int i;
                String zip;
                int prevCount;
                int newCount;
    
                List<String> zipList = new ArrayList<String> ();
                for (i = 0; i < eventList.length; i++) {
                    zip = eventList[i].event.zip_code;
                    newCount = eventList[i].event.count;
                    prevCount = Launcher.zipCodeCases.get(zip);
                    if (newCount >= prevCount) {
                        zipList.add(zip);
                    }
                    Launcher.zipCodeCases.put(zip, prevCount + newCount);
                }
                Launcher.zipList = zipList;
            } else if (this.streamName == "StatusAlertStream") {
                Gson gson = new Gson();
                StatusQuery[] statusList = gson.fromJson(msg.toString(), StatusQuery[].class);

                int i;
                for (i = 0; i < statusList.length; i++) {
                    switch(statusList[i].event.patient_status_code) {
                        case "0":
                            break;
                        case "1":
                            Launcher.negativeCases = Launcher.negativeCases + statusList[i].event.count;
                            break;
                        case "2":
                            Launcher.positiveCases = Launcher.positiveCases + statusList[i].event.count;
                            break;
                        case "3":
                            Launcher.negativeCases = Launcher.negativeCases + statusList[i].event.count;
                            break;
                        case "4":
                            Launcher.negativeCases = Launcher.negativeCases + statusList[i].event.count;
                            break;
                        case "5":
                            Launcher.positiveCases = Launcher.positiveCases + statusList[i].event.count;
                            break;
                        case "6":
                            Launcher.positiveCases = Launcher.positiveCases + statusList[i].event.count;
                            break;
                        default:
                    }
                } 
            } else if (this.streamName == "PatientOutStream") {
                Gson gson = new Gson();
                PatientQuery patientList = gson.fromJson(msg.toString(), PatientQuery.class);

                Launcher.createPatient(patientList.event);
                
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }

    }

    @Override
    public String getTopic() {
        return topic;
    }

}
