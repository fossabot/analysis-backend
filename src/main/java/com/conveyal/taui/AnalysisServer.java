package com.conveyal.taui;

import com.auth0.jwt.JWTVerifier;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.util.FeedSourceCache;
import com.conveyal.taui.analysis.LocalCluster;
import com.conveyal.taui.controllers.AggregationAreaController;
import com.conveyal.taui.controllers.BundleController;
import com.conveyal.taui.controllers.GraphQLController;
import com.conveyal.taui.controllers.OpportunityDatasetsController;
import com.conveyal.taui.controllers.ModificationController;
import com.conveyal.taui.controllers.ProjectController;
import com.conveyal.taui.controllers.RegionalAnalysisController;
import com.conveyal.taui.controllers.ScenarioController;
import com.conveyal.taui.controllers.SinglePointAnalysisController;
import com.conveyal.taui.persistence.OSMPersistence;
import com.conveyal.taui.persistence.Persistence;
import com.google.common.io.CharStreams;
import org.apache.commons.codec.binary.Base64;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import static com.conveyal.taui.util.SparkUtil.haltWithJson;
import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.port;

/**
 * This is the main entry point for starting a Conveyal Analysis server.
 */
public class AnalysisServer {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisServer.class);

    public static void main (String... args) throws Exception {

        LOG.info("Starting Conveyal Analysis server, the time is now {}", DateTime.now());

        byte[] auth0Secret = new Base64(true).decode(AnalysisServerConfig.auth0Secret);
        String auth0ClientId = AnalysisServerConfig.auth0ClientId;

        LOG.info("Connecting to database...");
        Persistence.initialize();

        LOG.info("Initializing GTFS cache...");
        File cacheDir = new File(AnalysisServerConfig.localCache);
        cacheDir.mkdirs();

        if (AnalysisServerConfig.offline) {
          FeedSourceCache feedSourceCache = ApiMain.initialize(null, AnalysisServerConfig.localCache);

          LOG.info("Starting local cluster of Analysis workers...");
          // TODO port is hardwired here and also in SinglePointAnalysisController
          // You have to make the worker machineId non-static if you want to launch more than one worker.
          new LocalCluster(6001, feedSourceCache, OSMPersistence.cache, 1);
        } else {
          ApiMain.initialize(AnalysisServerConfig.bundleBucket, AnalysisServerConfig.localCache);
        }

        // Set the port on which the HTTP server will listen for connections.
        LOG.info("Analysis server will listen for HTTP connections on port {}.", AnalysisServerConfig.port);
        port(AnalysisServerConfig.port);

        // initialize ImageIO
        // http://stackoverflow.com/questions/20789546
        ImageIO.scanForPlugins();

        // check if a user is authenticated
        before((req, res) -> {
            if (!req.pathInfo().startsWith("/api")) return; // don't need to be authenticated to view main page

            if (!AnalysisServerConfig.offline) {
                String auth = req.headers("Authorization");

                // authorization required
                if (auth == null || auth.isEmpty()) {
                    haltWithJson(401, "You must be logged in.");
                }

                // make sure it's properly formed
                String[] authComponents = auth.split(" ");

                if (authComponents.length != 2 || !"bearer".equals(authComponents[0].toLowerCase())) {
                    haltWithJson(400, "Authorization header is malformed: " + auth);
                }

                // validate the JWT
                JWTVerifier verifier = new JWTVerifier(auth0Secret, auth0ClientId);

                Map<String, Object> jwt = null;
                try {
                    jwt = verifier.verify(authComponents[1]);
                } catch (Exception e) {
                    LOG.info("Login failed", e);
                    haltWithJson(403, "Login failed to verify with our authorization provider.");
                }

                if (!jwt.containsKey("analyst")) {
                    haltWithJson(403, "Access denied. User does not have access to Analysis.");
                }

                String group = null;
                try {
                    group = (String) ((Map<String, Object>) jwt.get("analyst")).get("group");
                } catch (Exception e) {
                    haltWithJson(403, "Access denied. User is not associated with any group.");
                }

                if (group == null) haltWithJson(403, "Access denied. User is not associated with any group.");

                req.attribute("group", group);
            } else {
                // hardwire group name if we're working offline
                req.attribute("group", "OFFLINE");
            }

            // Default is JSON, will be overridden by the few controllers that do not return JSON
            res.type("application/json");
        });

        // Register all our HTTP request handlers with the Spark HTTP framework.
        ProjectController.register();
        ModificationController.register();
        ScenarioController.register();
        GraphQLController.register();
        BundleController.register();
        SinglePointAnalysisController.register();
        OpportunityDatasetsController.register();
        RegionalAnalysisController.register();
        AggregationAreaController.register();

        // Load index.html and register a handler with Spark to serve it up.
        InputStream indexStream = AnalysisServer.class.getClassLoader().getResourceAsStream("public/index.html");
        String index = CharStreams.toString(
                new InputStreamReader(indexStream)).replace("${ASSET_LOCATION}", AnalysisServerConfig.assetLocation);
        indexStream.close();
        get("/*", (req, res) -> { res.type("text/html"); return index; });
        LOG.info("Conveyal Analysis server is ready.");
    }
}
