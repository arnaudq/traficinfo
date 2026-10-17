package org.traficinfo.dsfunctions.m3m;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.dsfunctions.OSMInfoProviderImpl;
import org.traficinfo.dsfunctions.OpenStreetMapClient;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public class GeoJsonOutFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_IN_BUCKET = "BUCKETNAME_IN";
    public static final String CONFIG_KEY_OUT_BUCKET = "BUCKETNAME_OUT";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    public static final String CONFIG_KEY_OSM_BUCKET = "BUCKETNAME_OSM";
    public static final String CONFIG_KEY_OSM_CACHE_OBJECT = "OSM_CACHE_OBJECT";
    public static final String DEFAULT_OSM_CACHE_OBJECT = "MMM_OSMInfo.json";

    private static final Logger LOG = LoggerFactory.getLogger(GeoJsonOutFunction.class);

    private ObjectStoreClient inputClient;
    private ObjectStoreClient outputClient;
    private OSMInfoProviderImpl osmInfoProvider;

    public GeoJsonOutFunction() {
        LOG.info("GeoJsonOut3M Function booting");
    }

    GeoJsonOutFunction(ObjectStoreClient inputClient, ObjectStoreClient outputClient,
                       OSMInfoProviderImpl osmInfoProvider) {
        this.inputClient = inputClient;
        this.outputClient = outputClient;
        this.osmInfoProvider = osmInfoProvider;
    }

    @FnConfiguration
    public void config(RuntimeContext ctx) {
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String inputBucket = ctx.getConfigurationByKey(CONFIG_KEY_IN_BUCKET).orElse("");
        String outputBucket = ctx.getConfigurationByKey(CONFIG_KEY_OUT_BUCKET).orElse("");
        String osmBucket = ctx.getConfigurationByKey(CONFIG_KEY_OSM_BUCKET).orElse("osminfo");
        String osmCacheObject = ctx.getConfigurationByKey(CONFIG_KEY_OSM_CACHE_OBJECT)
                .orElse(DEFAULT_OSM_CACHE_OBJECT);
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));

        inputClient = new ObjectStoreClient(useResourcePrincipal, namespace, inputBucket);
        outputClient = new ObjectStoreClient(useResourcePrincipal, namespace, outputBucket);
        osmInfoProvider = new OSMInfoProviderImpl(
                new ObjectStoreClient(useResourcePrincipal, namespace, osmBucket),
                new OpenStreetMapClient(), osmCacheObject);
        LOG.info("GeoJsonOut3M Function configured. Namespace = {}, Input bucket = {}, Output bucket = {}",
                namespace, inputBucket, outputBucket);
    }

    public String handleRequest(String input) {
        try {
            osmInfoProvider.loadCache();
            Set<Long> waysToRefresh = new LinkedHashSet<>();
            Path mergedFile = Files.createTempFile("m3m-geojson-", ".geojson");
            try {
                try (Reader csvReader = new InputStreamReader(
                        inputClient.getResource("MMM_MMM_Comptage.csv"), StandardCharsets.UTF_8);
                     Reader geojsonReader = new InputStreamReader(
                        inputClient.getResource("MMM_MMM_Comptage.json"), StandardCharsets.UTF_8);
                     Writer writer = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                    GeoJsonParser.parseAndMerge(GeoJsonParser.DataSource.M3MV2,
                            csvReader, geojsonReader, writer, osmInfoProvider, waysToRefresh);
                }
                try (InputStream mergedInput = Files.newInputStream(mergedFile)) {
                    outputClient.putResource("MMM_TraficInfo.geojson", "application/geo+json",
                            new OOSResourceMetadata(), mergedInput);
                }
            } finally {
                Files.deleteIfExists(mergedFile);
            }
            osmInfoProvider.refreshCache(waysToRefresh);
            return "OK";
        } catch (Exception failure) {
            LOG.warn("Error while generating GeoJSON: {}", failure.getMessage(), failure);
            return "KO";
        }
    }
}
