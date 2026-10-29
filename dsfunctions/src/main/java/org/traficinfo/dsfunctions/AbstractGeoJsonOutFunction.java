package org.traficinfo.dsfunctions;

import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.RuntimeContext;
import org.geojson.FeatureCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traficinfo.parsers.GeoJsonParser;

import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared manifest-to-GeoJSON publication with cached OSM enrichment. */
public abstract class AbstractGeoJsonOutFunction {

    public static final String CONFIG_KEY_NAMESPACE = "NAMESPACE";
    public static final String CONFIG_KEY_IN_BUCKET = "BUCKETNAME_IN";
    public static final String CONFIG_KEY_OUT_BUCKET = "BUCKETNAME_OUT";
    public static final String CONFIG_KEY_USE_RESOURCE_PRINCIPAL = "USE_RESOURCE_PRINCIPAL";
    public static final String DEFAULT_OUTPUT_BUCKET = "traficinfo";

    private static final Logger LOG = LoggerFactory.getLogger(AbstractGeoJsonOutFunction.class);

    private final CsvDataSource source;

    private ObjectStoreClient inputClient;
    private ObjectStoreClient outputClient;
    private OSMInfoProviderImpl osmInfoProvider;

    protected AbstractGeoJsonOutFunction(CsvDataSource source) {
        this.source = Objects.requireNonNull(source);
        LOG.info("GeoJSON {} function booting", source.getDataSourceId());
    }

    protected AbstractGeoJsonOutFunction(CsvDataSource source, ObjectStoreClient inputClient,
                                           ObjectStoreClient outputClient, OSMInfoProviderImpl osmInfoProvider) {
        this(source);
        this.inputClient = Objects.requireNonNull(inputClient);
        this.outputClient = Objects.requireNonNull(outputClient);
        this.osmInfoProvider = Objects.requireNonNull(osmInfoProvider);
    }

    protected ObjectStoreClient createObjectStoreClient(boolean useResourcePrincipal,
                                                       String namespace, String bucket) {
        return new ObjectStoreClient(useResourcePrincipal, namespace, bucket);
    }

    @FnConfiguration
    public final void config(RuntimeContext ctx) {
        String namespace = ctx.getConfigurationByKey(CONFIG_KEY_NAMESPACE).orElse("");
        String inputBucket = ctx.getConfigurationByKey(CONFIG_KEY_IN_BUCKET).orElse(source.getDefaultInputBucket());
        String outputBucket = ctx.getConfigurationByKey(CONFIG_KEY_OUT_BUCKET).orElse(DEFAULT_OUTPUT_BUCKET);
        boolean useResourcePrincipal = Boolean.parseBoolean(
                ctx.getConfigurationByKey(CONFIG_KEY_USE_RESOURCE_PRINCIPAL).orElse("true"));
        inputClient = createObjectStoreClient(useResourcePrincipal, namespace, inputBucket);
        outputClient = createObjectStoreClient(useResourcePrincipal, namespace, outputBucket);
        String osmBucket = ctx.getConfigurationByKey("BUCKETNAME_OSM").orElse("osminfo");
        String tagObject = ctx.getConfigurationByKey("OSM_CACHE_OBJECT").orElse(source.getOsmCacheObjectName());
        String mappingObject = ctx.getConfigurationByKey("OSM_WAY_MAPPING_OBJECT")
                .orElse(source.getOsmWayMappingObjectName());
        osmInfoProvider = new OSMInfoProviderImpl(
                createObjectStoreClient(useResourcePrincipal, namespace, osmBucket),
                new OpenStreetMapClient(), tagObject, mappingObject);
        LOG.info("GeoJSON {} configured. Namespace = {}, Input bucket = {}, Output bucket = {}",
                source.getDataSourceId(), namespace, inputBucket, outputBucket);
    }

    public final String handleRequest(String input) {
        try {
            var comptages = new ManifestComptageReader(inputClient).read(source.getDataSourceId());
            osmInfoProvider.loadCache();
            Path mergedFile = Files.createTempFile(source.getDataSourceId() + "-geojson-", ".geojson");
            try {
                try (Writer geojsonMergedWriter = Files.newBufferedWriter(mergedFile, StandardCharsets.UTF_8)) {
                    GeoJsonParser.writeGeoJson(source.getParserDataSource(), comptages,
                            new FeatureCollection(), geojsonMergedWriter, osmInfoProvider);
                }
                try (InputStream mergedInput = Files.newInputStream(mergedFile)) {
                    outputClient.putResource(source.getGeoJsonObjectName(), "application/geo+json",
                            new OOSResourceMetadata(), mergedInput);
                }
            } finally {
                Files.deleteIfExists(mergedFile);
            }
            osmInfoProvider.refreshCache();
            return "OK";
        } catch (Exception failure) {
            LOG.warn("Error while generating {} GeoJSON: {}", source.getDataSourceId(),
                    failure.getMessage(), failure);
            return "KO";
        }
    }
}
