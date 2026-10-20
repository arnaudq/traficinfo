package org.traficinfo.dsfunctions.dpt34;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.ObjectStoreClient;
import org.traficinfo.dsfunctions.OOSResourceMetadata;
import org.traficinfo.parsers.GeoJsonParser;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class GeoJsonOutFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_IN_BUCKET = "BUCKETNAME_IN";
    public static final String CONFIG_KEY_OUT_BUCKET = "BUCKETNAME_OUT";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    public static final String DEFAULT_INPUT_BUCKET = "dsdpt34";
    public static final String DEFAULT_OUTPUT_BUCKET = "traficinfo";
    public static final String GEOJSON_OBJECT_NAME = "DPT34_TraficInfo.geojson";

    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonOutFunction.class);

    private ObjectStoreClient inputClient;
    private ObjectStoreClient outputClient;
    private OSMInfoProviderImpl osmInfoProvider;

    public GeoJsonOutFunction() {
        LOG.info("GeoJsonOutDPT34 Function booting");
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient, OSMInfoProviderImpl osmInfoProvider) {
        this.inputClient = inputClient;
        this.outputClient = outputClient;
        this.osmInfoProvider = osmInfoProvider;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String inputBucket = ctx.getConfigurationByKey(CONFIG_KEY_IN_BUCKET).orElse(DEFAULT_INPUT_BUCKET);
        String outputBucket = ctx.getConfigurationByKey(CONFIG_KEY_OUT_BUCKET).orElse(DEFAULT_OUTPUT_BUCKET);
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));
        inputClient = new ObjectStoreClient(useResourcePrincipal, namespace, inputBucket);
        outputClient = new ObjectStoreClient(useResourcePrincipal, namespace, outputBucket);
        String osmBucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        String tagObject = ctx.getConfigurationByKey("OSM_CACHE_OBJECT").orElse("DPT34_OSMInfo.json");
        String mappingObject = ctx.getConfigurationByKey("OSM_WAY_MAPPING_OBJECT")
                .orElse("DPT34_OSMWayMapping.json");
        osmInfoProvider = new OSMInfoProviderImpl(
                new ObjectStoreClient(useResourcePrincipal, namespace, osmBucket),
                new OpenStreetMapClient(), tagObject, mappingObject);
        LOG.info("GeoJsonOutDPT34 configured. Namespace = {}, Input bucket = {}, Output bucket = {}",
                namespace, inputBucket, outputBucket);
    }

    public String handleRequest(String input) {
        try {
            osmInfoProvider.loadCache();
            Path mergedFile = Files.createTempFile("dpt34-geojson-", ".geojson");
            try {
                try (Reader csvReader = new InputStreamReader(
                        inputClient.getResource(DataSourceFunction.CSV_OBJECT_NAME), StandardCharsets.UTF_8);
                     Writer writer = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.DPT34, csvReader, writer, osmInfoProvider);
                }
                try (InputStream mergedInput = Files.newInputStream(mergedFile)) {
                    outputClient.putResource(GEOJSON_OBJECT_NAME, "application/geo+json",
                            new OOSResourceMetadata(), mergedInput);
                }
            } finally {
                Files.deleteIfExists(mergedFile);
            }
            osmInfoProvider.refreshCache();
            return "OK";
        } catch (Exception failure) {
            LOG.warn("Error while generating DPT34 GeoJSON: {}", failure.getMessage(), failure);
            return "KO";
        }
    }
}
