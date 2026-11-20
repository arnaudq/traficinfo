package org.traficinfo.dsfunctions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Manually refreshes all departements and the EPCI boundaries in bundled mapconfig.json. */
public class BoundaryFunction extends TraficInfoBaseFunction {
    private static final Logger LOG = LoggerFactory.getLogger(BoundaryFunction.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern ID = Pattern.compile("(departement|epci):([A-Za-z0-9]+)");
    static final URI DEPARTEMENTS_URL = URI.create("https://etalab-datasets.geo.data.gouv.fr/"
            + "contours-administratifs/latest/geojson/departements-100m.geojson");
    static final String DEPARTEMENTS_OBJECT = "boundaries/departements.geojson";
    private static final String ATTRIBUTION_OBJECT = "boundaries/attribution.json";
    private static final int MAX_AGE_MONTHS = 3;
    private final Clock clock;
    private List<Boundary> boundaries;
    private String bucket;
    private String namespace;
    private boolean principal;

    public BoundaryFunction() {
        this(Clock.systemUTC());
    }

    BoundaryFunction(Clock clock) {
        super("ALL");
        this.clock = clock;
    }

    protected JsonNode loadMapConfig() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mapconfig.json")) {
            if (input == null) throw new IOException("Missing bundled mapconfig.json");
            return JSON.readTree(input);
        }
    }

    protected ObjectStoreClient createObjectStoreClient(boolean usePrincipal, String ns, String targetBucket) {
        return new ObjectStoreClient(usePrincipal, ns, targetBucket);
    }

    protected HttpClient createHttpClient() {
        return new HttpClient();
    }

    @Override
    protected void configure(RuntimeContext context) {
        try {
            JsonNode config = loadMapConfig();
            URI base = URI.create(config.path("geoJsonBaseUrl").asText());
            var destination = Pattern.compile("/n/[^/]+/b/([^/]+)/o/?").matcher(base.getPath());
            if (!"https".equals(base.getScheme()) || !destination.matches()) {
                throw new IllegalArgumentException("geoJsonBaseUrl must identify the public OCI bucket");
            }
            bucket = destination.group(1);
            boundaries = readBoundaries(config.path("boundaries"));
            namespace = context.getConfigurationByKey("NAMESPACE")
                    .filter(value -> !value.isBlank()).orElseThrow(() -> new IllegalArgumentException("NAMESPACE is required"));
            principal = Boolean.parseBoolean(context.getConfigurationByKey("USE_RESOURCE_PRINCIPAL").orElse("true"));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot load bundled mapconfig.json", failure);
        }
    }

    static List<Boundary> readBoundaries(JsonNode definitions) {
        if (!definitions.isObject() || definitions.isEmpty()) {
            throw new IllegalArgumentException("boundaries must be a nonempty object");
        }
        List<Boundary> result = new ArrayList<>();
        var paths = new HashSet<String>();
        definitions.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode definition = entry.getValue();
            var match = ID.matcher(id);
            if (!match.matches() || !id.equals(definition.path("boundaryId").asText())) {
                throw new IllegalArgumentException("Unsupported or mismatched boundaryId: " + id);
            }
            String scheme = match.group(1);
            String expectedType = scheme.equals("epci") ? "city" : "region";
            if (!expectedType.equals(definition.path("type").asText())) {
                throw new IllegalArgumentException("Invalid boundary type: " + id);
            }
            String path = definition.path("geoJsonPath").asText();
            if (!path.startsWith("boundaries/") || !path.endsWith(".geojson")
                    || List.of(path.split("/")).contains("..") || path.contains("?") || path.contains("#")
                    || !paths.add(path)) {
                throw new IllegalArgumentException("Invalid or duplicate geoJsonPath: " + path);
            }
            URI url = scheme.equals("departement") ? DEPARTEMENTS_URL
                    : URI.create("https://geo.api.gouv.fr/epcis/" + match.group(2)
                            + "?format=geojson&geometry=contour");
            result.add(new Boundary(id, match.group(2), path, url));
        });
        return List.copyOf(result);
    }

    protected byte[] download(URI url) throws IOException {
        try (HttpClient http = createHttpClient();
             HttpClient.EntityBody body = http.get(url, null)) {
            if (body.getInputStream() == null) throw new IOException("Missing boundary response body: " + url);
            return body.getInputStream().readAllBytes();
        }
    }

    private static Request readRequest(String input) {
        try {
            JsonNode request = JSON.readTree(input == null ? "" : input);
            if (request == null || !request.isObject()) throw new IllegalArgumentException("Expected a boundary request object");
            var fields = request.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!field.equals("epciCode")) {
                    throw new IllegalArgumentException("Unknown boundary request field: " + field);
                }
            }
            String epciCode = null;
            if (request.has("epciCode")) {
                JsonNode code = request.get("epciCode");
                if (!code.isTextual() || !code.asText().matches("[0-9]{9}")) {
                    throw new IllegalArgumentException("epciCode must be a nine-digit string");
                }
                epciCode = code.asText();
            }
            return new Request(epciCode);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Expected {} or {\"epciCode\":\"...\"}", invalid);
        }
    }

    @Override
    protected String processRequest(String input, Invocation invocation) {
        if (boundaries == null) throw new IllegalStateException("Boundary function has not been configured");
        Request request = readRequest(input);
        Instant fetchedAt = clock.instant();
        Instant cutoff = fetchedAt.atZone(ZoneOffset.UTC).minusMonths(MAX_AGE_MONTHS).toInstant();
        List<ObjectNode> updates = new ArrayList<>();
        try (ObjectStoreClient storage = createObjectStoreClient(principal, namespace, bucket)) {
            ObjectNode dataset = request.epciCode() == null
                    ? refreshDepartements(storage, cutoff, fetchedAt, updates) : null;
            List<Boundary> selected = request.epciCode() == null
                    ? boundaries.stream().filter(boundary -> boundary.id().startsWith("epci:")).toList()
                    : List.of(epciBoundary(request.epciCode()));
            for (Boundary boundary : selected) {
                if (fresh(fetchDate(storage.getResourceMetadata(boundary.path())), cutoff)) {
                    LOG.info("Boundary {} is fresh; skipping download", boundary.id());
                    continue;
                }
                LOG.info("Downloading boundary {} from {}", boundary.id(), boundary.url());
                byte[] feature = prepareFeature(boundary, download(boundary.url()));
                updates.add(publishBoundary(storage, boundary, feature, fetchedAt));
            }
            if (!updates.isEmpty()) updateAttribution(storage, updates, dataset, fetchedAt);
            return "OK";
        } catch (IOException | RuntimeException failure) {
            LOG.error("Boundary refresh failed", failure);
            throw new IllegalStateException("Boundary refresh failed", failure);
        }
    }

    private Boundary epciBoundary(String code) {
        String id = "epci:" + code;
        return boundaries.stream().filter(boundary -> boundary.id().equals(id)).findFirst()
                .orElseGet(() -> new Boundary(id, code, "boundaries/epcis/" + code + ".geojson",
                        URI.create("https://geo.api.gouv.fr/epcis/" + code + "?format=geojson&geometry=contour")));
    }

    private static Instant fetchDate(OOSResourceMetadata metadata) {
        if (metadata == null) return null;
        String value = metadata.getMetadata().get("downloaded-at");
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static boolean fresh(Instant fetchedAt, Instant cutoff) {
        return fetchedAt != null && !fetchedAt.isBefore(cutoff);
    }

    private static OOSResourceMetadata metadata(URI source, Instant fetchedAt) {
        var metadata = new OOSResourceMetadata();
        metadata.getMetadata().put("source-url", source.toString());
        metadata.getMetadata().put("downloaded-at", fetchedAt.toString());
        return metadata;
    }

    private ObjectNode refreshDepartements(ObjectStoreClient storage, Instant cutoff,
                                           Instant now, List<ObjectNode> updates) throws IOException {
        Instant fetchedAt = fetchDate(storage.getResourceMetadata(DEPARTEMENTS_OBJECT));
        boolean download = !fresh(fetchedAt, cutoff);
        byte[] bytes;
        if (download) {
            LOG.info("Downloading all departement boundaries from {}", DEPARTEMENTS_URL);
            bytes = download(DEPARTEMENTS_URL);
            fetchedAt = now;
        } else {
            LOG.info("Using fresh departement dataset from {}", DEPARTEMENTS_OBJECT);
            try (InputStream input = storage.getResource(DEPARTEMENTS_OBJECT)) {
                bytes = input.readAllBytes();
            }
        }
        JsonNode departements = JSON.readTree(bytes);
        for (Boundary boundary : publicationBoundaries(departements)) {
            if (!boundary.url().equals(DEPARTEMENTS_URL)) continue;
            if (!download && fresh(fetchDate(storage.getResourceMetadata(boundary.path())), cutoff)) continue;
            updates.add(publishBoundary(storage, boundary, prepareFeature(boundary, departements), fetchedAt));
        }
        if (download) {
            storage.putResource(DEPARTEMENTS_OBJECT, "application/geo+json", metadata(DEPARTEMENTS_URL, fetchedAt),
                    new ByteArrayInputStream(bytes));
        }
        return JSON.createObjectNode().put("geoJsonPath", DEPARTEMENTS_OBJECT)
                .put("sourceUrl", DEPARTEMENTS_URL.toString()).put("downloadedAt", fetchedAt.toString())
                .put("simplificationMetres", 100);
    }

    private ObjectNode publishBoundary(ObjectStoreClient storage, Boundary boundary, byte[] feature,
                                       Instant fetchedAt) throws IOException {
        var metadata = metadata(boundary.url(), fetchedAt);
        metadata.getMetadata().put("boundary-id", boundary.id());
        storage.putResource(boundary.path(), "application/geo+json", metadata, new ByteArrayInputStream(feature));
        LOG.info("Published boundary {} to {}/{}", boundary.id(), bucket, boundary.path());
        var file = JSON.createObjectNode().put("boundaryId", boundary.id()).put("geoJsonPath", boundary.path())
                .put("sourceUrl", boundary.url().toString()).put("downloadedAt", fetchedAt.toString());
        if (boundary.url().equals(DEPARTEMENTS_URL)) file.put("simplificationMetres", 100);
        else file.putNull("simplificationMetres");
        return file;
    }

    private static void updateAttribution(ObjectStoreClient storage, List<ObjectNode> updates,
                                           ObjectNode dataset, Instant now) throws IOException {
        ObjectNode attribution;
        if (storage.getResourceMetadata(ATTRIBUTION_OBJECT) == null) {
            attribution = JSON.createObjectNode();
            attribution.put("source", "https://www.data.gouv.fr/datasets/contours-administratifs");
            attribution.put("licence", "ODbL-1.0");
            attribution.put("attribution", "IGN ADMIN EXPRESS; data.gouv.fr / API Découpage administratif");
            attribution.putNull("releaseDate");
            attribution.putNull("simplificationMetres");
        } else {
            try (InputStream input = storage.getResource(ATTRIBUTION_OBJECT)) {
                JsonNode existing = JSON.readTree(input);
                if (!(existing instanceof ObjectNode object)) throw new IOException("Invalid boundary attribution object");
                attribution = object;
            }
        }
        var entries = new LinkedHashMap<String, JsonNode>();
        for (JsonNode entry : attribution.path("files")) entries.put(entry.path("geoJsonPath").asText(), entry);
        for (ObjectNode update : updates) entries.put(update.path("geoJsonPath").asText(), update);
        var files = attribution.putArray("files");
        entries.values().forEach(files::add);
        attribution.remove("downloadedAt");
        attribution.put("updatedAt", now.toString());
        if (dataset != null) attribution.set("departementDataset", dataset);
        storage.putResource(ATTRIBUTION_OBJECT, "application/json", new OOSResourceMetadata(),
                new ByteArrayInputStream(JSON.writeValueAsBytes(attribution)));
    }

    record Request(String epciCode) {}

    static byte[] prepareFeature(Boundary boundary, byte[] bytes) throws IOException {
        return prepareFeature(boundary, JSON.readTree(bytes));
    }

    private List<Boundary> publicationBoundaries(JsonNode departements) throws IOException {
        if (departements == null || !"FeatureCollection".equals(departements.path("type").asText())
                || !departements.path("features").isArray() || departements.path("features").isEmpty()) {
            throw new IOException("Expected the departement FeatureCollection: " + DEPARTEMENTS_URL);
        }
        var result = new LinkedHashMap<String, Boundary>();
        boundaries.forEach(boundary -> result.put(boundary.id(), boundary));
        var codes = new HashSet<String>();
        for (JsonNode feature : departements.get("features")) {
            String code = feature.path("properties").path("code").asText();
            if (!code.matches("[A-Za-z0-9]+") || !codes.add(code)) {
                throw new IOException("Invalid or duplicate departement identifier: " + code);
            }
            String id = "departement:" + code;
            result.putIfAbsent(id, new Boundary(id, code, "boundaries/departements/" + code + ".geojson", DEPARTEMENTS_URL));
        }
        var paths = new HashSet<String>();
        for (Boundary boundary : result.values()) {
            if (!paths.add(boundary.path())) throw new IOException("Duplicate output path: " + boundary.path());
        }
        return List.copyOf(result.values());
    }

    private static byte[] prepareFeature(Boundary boundary, JsonNode feature) throws IOException {
        if (feature != null && "FeatureCollection".equals(feature.path("type").asText())) {
            JsonNode features = feature.path("features");
            if (!features.isArray()) throw new IOException("Expected a features array: " + boundary.url());
            JsonNode selected = null;
            for (JsonNode candidate : features) {
                if (boundary.sourceId().equals(candidate.path("properties").path("code").asText())) {
                    if (selected != null) throw new IOException("Duplicate source identifier: " + boundary.id());
                    selected = candidate;
                }
            }
            feature = selected;
        }
        if (feature == null || !feature.isObject() || !"Feature".equals(feature.path("type").asText())
                || !feature.path("properties").isObject()
                || !boundary.sourceId().equals(feature.path("properties").path("code").asText())) {
            throw new IOException("Expected a GeoJSON Feature for " + boundary.id());
        }
        JsonNode geometry = feature.path("geometry");
        String type = geometry.path("type").asText();
        JsonNode polygons = type.equals("Polygon") ? JSON.createArrayNode().add(geometry.path("coordinates"))
                : geometry.path("coordinates");
        if ((!type.equals("Polygon") && !type.equals("MultiPolygon")) || !polygons.isArray() || polygons.isEmpty()) {
            throw new IOException("Expected nonempty Polygon or MultiPolygon for " + boundary.id());
        }
        for (JsonNode polygon : polygons) {
            if (!polygon.isArray() || polygon.isEmpty()) throw new IOException("Empty polygon: " + boundary.id());
            for (JsonNode ring : polygon) {
                if (!ring.isArray() || ring.size() < 4 || !ring.get(0).equals(ring.get(ring.size() - 1))) {
                    throw new IOException("Invalid polygon ring: " + boundary.id());
                }
                for (JsonNode point : ring) {
                    if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()
                            || !Double.isFinite(point.get(0).asDouble()) || !Double.isFinite(point.get(1).asDouble())
                            || Math.abs(point.get(0).asDouble()) > 180 || Math.abs(point.get(1).asDouble()) > 90) {
                        throw new IOException("Invalid WGS84 coordinates: " + boundary.id());
                    }
                }
            }
        }
        ObjectNode output = feature.deepCopy();
        ((ObjectNode) output.get("properties")).put("boundaryId", boundary.id());
        return JSON.writeValueAsBytes(output);
    }

    record Boundary(String id, String sourceId, String path, URI url) {}
}
