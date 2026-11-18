package org.traficinfo.dsfunctions;

import com.fnproject.fn.runtime.FunctionConfigurer;
import com.fnproject.fn.runtime.FunctionLoader;
import com.fnproject.fn.runtime.FunctionRuntimeContext;
import org.junit.Test;
import org.traficinfo.parsers.onisr.ONISRAccidentJson;
import java.util.*;
import static org.junit.Assert.*;

public class AccidentPublicationFunctionTest {
    @Test public void publishesANonOnisrSourceUsingItsConfiguredBucketAndId() throws Exception {
        var context = new FunctionRuntimeContext(new FunctionLoader().loadClass(GenericProbe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE", "namespace", "BUCKETNAME_OUT", "public-output"));
        new FunctionConfigurer().configure(context);
        GenericProbe probe = (GenericProbe) context.getInvokeInstance().orElseThrow();
        probe.input.objects.put("CITY_A_Accidents.json", ("{\"77\":[{\"accidentId\":\"city-collision-A\","
                + "\"date\":\"2025-01-02\",\"time\":null,\"regionalId\":\"zone-A\","
                + "\"latitude\":51.5,\"longitude\":-0.1,\"osmWayId\":77,"
                + "\"participants\":[{\"participantId\":\"person-A\",\"vehicleId\":\"bike-A\","
                + "\"role\":\"DRIVER\",\"injurySeverity\":\"HOSPITALIZED\"}],"
                + "\"vehicles\":[{\"vehicleId\":\"bike-A\",\"type\":\"BICYCLE\"}]}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("OK", probe.handleRequest("{}"));
        assertEquals(List.of("public-output", "private-accidents"), probe.buckets);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var summary = json.readTree(probe.output.objects.get("CITY_A_Accidents_Summary.json"));
        assertEquals("CITY_A", summary.get("sourceId").asText());
        assertEquals(1, summary.at("/years/2025/participantCounts/CYCLIST/HOSPITALIZED").asInt());
        assertEquals("city-collision-A", json.readTree(probe.output.objects.get("CITY_A_Accidents.geojson"))
                .at("/features/0/id").asText());
    }

    public static class GenericProbe extends Probe {
        final List<String> buckets = new ArrayList<>();
        @Override protected List<AccidentsConfig.Source> loadSources() {
            return List.of(new AccidentsConfig.Source("CITY_A", "private-accidents"));
        }
        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal, String ns, String bucket) {
            buckets.add(bucket);
            return bucket.equals("private-accidents") ? input : output;
        }
    }

    @Test public void fnPublishesAllRegionsAndClosesStoresAndPropagatesMissingInput() throws Exception {
        var context = new FunctionRuntimeContext(new FunctionLoader().loadClass(Probe.class.getName(), "handleRequest"),
                Map.of("NAMESPACE","namespace","BUCKETNAME_OUT","public-output","USE_RESOURCE_PRINCIPAL","false"));
        new FunctionConfigurer().configure(context);
        Probe probe = (Probe) context.getInvokeInstance().orElseThrow();
        var codec = new ONISRAccidentJson();
        for(String region:List.of("30","34","76")) probe.input.objects.put("ONISR_DPT"+region+"_Accidents.json",codec.writeByWay(Map.of()));
        probe.input.objects.put("ONISR_DPT34_Accidents.json",codec.writeByWay(Map.of(77L,List.of(AccidentPublicationTest.accident(2025,1,77)))));
        assertEquals("OK",probe.handleRequest("{}"));
        assertEquals(List.of("false/namespace/public-output","false/namespace/onisrinfo","false/namespace/onisrinfo","false/namespace/onisrinfo"),probe.connections);
        assertEquals(6,probe.output.objects.size());
        assertTrue(probe.input.closed && probe.output.closed);
        probe.input.objects.remove("ONISR_DPT30_Accidents.json");
        try {probe.handleRequest("{}");fail("Missing source must fail invocation");}
        catch(com.oracle.bmc.model.BmcException expected){assertEquals(404,expected.getStatusCode());}
    }
    public static class Probe extends AccidentPublicationFunction {
        final Store input=new Store(),output=new Store();
        final List<String> connections=new ArrayList<>();
        @Override protected ObjectStoreClient createObjectStoreClient(boolean principal,String ns,String bucket){
            connections.add(principal+"/"+ns+"/"+bucket);
            return bucket.equals("onisrinfo")?input:output;
        }
    }
    static class Store extends AccidentPublicationTest.Store {
        boolean closed;
        @Override public void close(){closed=true;}
    }
}
