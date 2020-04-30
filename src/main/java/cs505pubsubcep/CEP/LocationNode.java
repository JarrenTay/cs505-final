package cs505pubsubcep.CEP;

public class LocationNode implements Comparable<LocationNode>{
    public String zip;
    public double distance;

    public LocationNode(String newZip, double newDistance) {
        this.zip = newZip;
        this.distance = newDistance;
    }

    public int compareTo(LocationNode otherNode) {
        if (this.distance < otherNode.distance) {
            return -1;
        } else if (this.distance > otherNode.distance) {
            return 1;
        } else {
            return 0;
        }
    }
}
/*
public class LocationCompare implements Comparator<HospitalNode> {
    public int compare(LocationNode node1, HospitalNode node2) {

    }
}*/
