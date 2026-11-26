package org.traficinfo.dsfunctions;

import java.io.*;

/** Reads private way-grouped records and publishes the two public derivatives. */
public final class AccidentPublicationPublisher {
    private final ObjectStore accidentStore;
    private final ObjectStore outputStore;

    public AccidentPublicationPublisher(ObjectStore accidentStore, ObjectStore outputStore) {
        this.accidentStore = accidentStore;
        this.outputStore = outputStore;
    }

    public void publish(String sourceId) throws IOException {
        String base = sourceId + "_Accidents";
        AccidentPublication.Output output;
        try (InputStream input = accidentStore.getResource(base + ".json")) {
            output = new AccidentPublication().render(sourceId, new AccidentPublicationJson().readByWay(input));
        }
        // Both artifacts are rendered and validated before replacing either public object.
        put(base + ".geojson", "application/geo+json", output.geojson());
        put(base + "_Summary.json", "application/json", output.summary());
    }

    private void put(String name, String type, byte[] content) throws IOException {
        try (InputStream input = new ByteArrayInputStream(content)) {
            outputStore.putResource(name, type, new ObjectMetadata(), input);
        }
    }
}
