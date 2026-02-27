/**
 * Java Transcription Starter - Backend Server
 *
 * This is a lightweight Javalin server that provides a transcription API endpoint
 * powered by Deepgram's Speech-to-Text service. It's designed to be easily
 * modified and extended for your own projects.
 *
 * Key Features:
 * - Single API endpoint: POST /api/transcription
 * - Accepts both file uploads and URLs
 * - CORS enabled for frontend communication
 * - JWT session auth with rate limiting (production only)
 * - Pure API server (frontend served separately)
 * - No Deepgram SDK -- direct HTTP calls via java.net.http.HttpClient
 */

package com.deepgram.starter;

// ============================================================================
// SECTION 1: IMPORTS
// ============================================================================

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================================
// SECTION 2: ENV LOADING
// ============================================================================

/**
 * Main application class for the Java Transcription Starter.
 * Uses java-dotenv to load environment variables from .env file.
 */
public class App {

    /** Dotenv instance for loading .env variables */
    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    /** Shared Jackson ObjectMapper for JSON serialization */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Shared HttpClient for making requests to the Deepgram API */
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ========================================================================
    // SECTION 3: CONFIGURATION
    // ========================================================================

    /**
     * Default transcription model to use when none is specified.
     * Options: "nova-3", "nova-2", "nova", "enhanced", "base"
     * See: https://developers.deepgram.com/docs/models-languages-overview
     */
    private static final String DEFAULT_MODEL = "nova-3";

    /** Server port, configurable via PORT env var (default 8081) */
    private static final int PORT = Integer.parseInt(
            getEnv("PORT", "8081"));

    /** Server host, configurable via HOST env var (default 0.0.0.0) */
    private static final String HOST = getEnv("HOST", "0.0.0.0");

    // ========================================================================
    // SECTION 4: SESSION AUTH - JWT tokens for production security
    // ========================================================================

    /**
     * Session secret for signing JWTs.
     * Auto-generated if SESSION_SECRET env var is not set.
     */
    private static final String SESSION_SECRET = initSessionSecret();

    /** JWT expiry time: 1 hour (in seconds) */
    private static final long JWT_EXPIRY_SECONDS = 3600;

    /** HMAC-SHA256 algorithm instance for JWT signing/verification */
    private static final Algorithm jwtAlgorithm = Algorithm.HMAC256(SESSION_SECRET);

    /** JWT verifier instance, reused for all token validations */
    private static final JWTVerifier jwtVerifier = JWT.require(jwtAlgorithm).build();

    /**
     * Initializes the session secret from env or generates a random one.
     * @return The session secret string
     */
    private static String initSessionSecret() {
        String secret = getEnv("SESSION_SECRET", null);
        if (secret != null && !secret.isEmpty()) {
            return secret;
        }
        // Generate random 32 bytes as hex
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Creates a signed JWT for session authentication.
     * @return Signed JWT string
     */
    private static String createSessionToken() {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(JWT_EXPIRY_SECONDS))
                .sign(jwtAlgorithm);
    }

    /**
     * Validates the JWT from the Authorization header.
     * Returns 401 with JSON error if token is missing or invalid.
     *
     * @param ctx Javalin request context
     * @return true if request should be blocked (401 sent), false if authorized
     */
    private static boolean requireSession(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of(
                    "error", Map.of(
                            "type", "AuthenticationError",
                            "code", "MISSING_TOKEN",
                            "message", "Authorization header with Bearer token is required"
                    )
            ));
            return true;
        }

        String token = authHeader.substring(7);
        try {
            jwtVerifier.verify(token);
            return false; // Authorized
        } catch (TokenExpiredException e) {
            ctx.status(401).json(Map.of(
                    "error", Map.of(
                            "type", "AuthenticationError",
                            "code", "INVALID_TOKEN",
                            "message", "Session expired, please refresh the page"
                    )
            ));
            return true;
        } catch (JWTVerificationException e) {
            ctx.status(401).json(Map.of(
                    "error", Map.of(
                            "type", "AuthenticationError",
                            "code", "INVALID_TOKEN",
                            "message", "Invalid session token"
                    )
            ));
            return true;
        }
    }

    // ========================================================================
    // SECTION 5: API KEY LOADING
    // ========================================================================

    /** The Deepgram API key loaded at startup */
    private static String apiKey;

    /**
     * Loads the Deepgram API key from environment variables.
     * Exits with a helpful error message if not found.
     *
     * @return The Deepgram API key
     */
    private static String loadApiKey() {
        String key = getEnv("DEEPGRAM_API_KEY", null);
        if (key == null || key.isEmpty() || key.equals("%api_key%")) {
            System.err.println();
            System.err.println("  ERROR: Deepgram API key not found!");
            System.err.println();
            System.err.println("Please set your API key using one of these methods:");
            System.err.println();
            System.err.println("1. Create a .env file (recommended):");
            System.err.println("   DEEPGRAM_API_KEY=your_api_key_here");
            System.err.println();
            System.err.println("2. Environment variable:");
            System.err.println("   export DEEPGRAM_API_KEY=your_api_key_here");
            System.err.println();
            System.err.println("Get your API key at: https://console.deepgram.com");
            System.err.println();
            System.exit(1);
        }
        return key;
    }

    // ========================================================================
    // SECTION 6: SETUP - Initialize Javalin app and CORS
    // ========================================================================

    // (Javalin app is created in the main method below)

    // ========================================================================
    // SECTION 7: HELPER FUNCTIONS
    // ========================================================================

    /**
     * Gets an environment variable with fallback to dotenv, then to a default.
     *
     * @param key          The environment variable name
     * @param defaultValue The default value if not found
     * @return The resolved value
     */
    private static String getEnv(String key, String defaultValue) {
        // System env takes priority (e.g., Docker, Fly.io)
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // Fall back to dotenv (.env file)
        try {
            value = dotenv.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignored) {
            // dotenv may not be available
        }
        return defaultValue;
    }

    /**
     * Validates that either a file or URL was provided in the request.
     *
     * @param file The uploaded file (may be null)
     * @param url  The URL string (may be null or empty)
     * @return A map describing the input, or null if neither provided
     */
    private static Map<String, Object> validateInput(UploadedFile file, String url) {
        // URL-based transcription
        if (url != null && !url.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("type", "url");
            result.put("url", url.trim());
            return result;
        }

        // File-based transcription
        if (file != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("type", "file");
            result.put("file", file);
            return result;
        }

        // Neither provided
        return null;
    }

    /**
     * Formats the raw Deepgram API response into a simplified, consistent
     * structure that the frontend expects.
     *
     * @param dgResponse The raw Deepgram JSON response as a JsonNode
     * @param modelName  The model name used for the transcription
     * @return A formatted response map
     * @throws Exception If the response structure is unexpected
     */
    private static Map<String, Object> formatResponse(JsonNode dgResponse, String modelName)
            throws Exception {
        // Navigate: results -> channels[0] -> alternatives[0]
        JsonNode results = dgResponse.path("results");
        JsonNode channels = results.path("channels");
        if (!channels.isArray() || channels.isEmpty()) {
            throw new Exception("No transcription results returned from Deepgram");
        }
        JsonNode alternatives = channels.get(0).path("alternatives");
        if (!alternatives.isArray() || alternatives.isEmpty()) {
            throw new Exception("No transcription results returned from Deepgram");
        }
        JsonNode alt = alternatives.get(0);

        // Build metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        JsonNode meta = dgResponse.path("metadata");
        if (meta.has("model_uuid")) {
            metadata.put("model_uuid", meta.get("model_uuid").asText());
        }
        if (meta.has("request_id")) {
            metadata.put("request_id", meta.get("request_id").asText());
        }
        metadata.put("model_name", modelName);

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transcript", alt.has("transcript") ? alt.get("transcript").asText() : "");
        response.put("words", objectMapper.treeToValue(
                alt.has("words") ? alt.get("words") : objectMapper.createArrayNode(),
                List.class));
        response.put("metadata", metadata);

        // Add optional duration if present
        if (meta.has("duration")) {
            response.put("duration", meta.get("duration").asDouble());
        }

        return response;
    }

    /**
     * Formats an error into a consistent JSON structure.
     *
     * @param message    Human-readable error message
     * @param statusCode HTTP status code
     * @param code       Error code string
     * @return A map suitable for JSON serialization
     */
    private static Map<String, Object> formatError(String message, int statusCode, String code) {
        String type = (statusCode == 400) ? "ValidationError" : "TranscriptionError";
        if (code == null || code.isEmpty()) {
            code = (statusCode == 400) ? "MISSING_INPUT" : "TRANSCRIPTION_FAILED";
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("originalError", message);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("code", code);
        error.put("message", message);
        error.put("details", details);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", error);
        return envelope;
    }

    // ========================================================================
    // SECTION 8: SESSION ROUTES
    // ========================================================================

    /**
     * GET /api/session - Issues a signed JWT for session authentication.
     *
     * @param ctx Javalin request context
     */
    private static void handleSession(Context ctx) {
        String token = createSessionToken();
        ctx.json(Map.of("token", token));
    }

    // ========================================================================
    // SECTION 9: API ROUTES
    // ========================================================================

    /**
     * POST /api/transcription
     *
     * Main transcription endpoint. Accepts either:
     * - A file upload (multipart/form-data with 'file' field)
     * - A URL to audio (form field 'url')
     *
     * Optional parameters:
     * - model: Deepgram model to use (default: "nova-3")
     *
     * Protected by JWT session auth (requireSession before handler).
     *
     * @param ctx Javalin request context
     */
    private static void handleTranscription(Context ctx) {
        try {
            // Get uploaded file (if any)
            UploadedFile file = ctx.uploadedFile("file");

            // Get URL from form data
            String url = ctx.formParam("url");

            // Get model from form data (default to nova-3)
            String model = ctx.formParam("model");
            if (model == null || model.trim().isEmpty()) {
                model = DEFAULT_MODEL;
            }

            // Validate input -- must have either file or URL
            Map<String, Object> input = validateInput(file, url);
            if (input == null) {
                ctx.status(400).json(formatError(
                        "Either file or url must be provided", 400, "MISSING_INPUT"));
                return;
            }

            // Build the Deepgram API URL with model query parameter
            String dgUrl = "https://api.deepgram.com/v1/listen?model=" + model;

            HttpRequest dgRequest;

            if ("url".equals(input.get("type"))) {
                // URL-based transcription: POST JSON body with url field
                String jsonBody = objectMapper.writeValueAsString(Map.of("url", input.get("url")));
                dgRequest = HttpRequest.newBuilder()
                        .uri(URI.create(dgUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Token " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();
            } else {
                // File-based transcription: POST raw bytes with file content type
                UploadedFile uploadedFile = (UploadedFile) input.get("file");
                byte[] fileBytes;
                try (InputStream is = uploadedFile.content()) {
                    fileBytes = is.readAllBytes();
                }
                String contentType = uploadedFile.contentType();
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "application/octet-stream";
                }

                dgRequest = HttpRequest.newBuilder()
                        .uri(URI.create(dgUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Token " + apiKey)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                        .build();
            }

            // Send request to Deepgram
            HttpResponse<String> dgResponse = httpClient.send(
                    dgRequest, HttpResponse.BodyHandlers.ofString());

            if (dgResponse.statusCode() != 200) {
                System.err.println("Deepgram API error: " + dgResponse.body());
                ctx.status(500).json(formatError(
                        "Deepgram API returned status " + dgResponse.statusCode(),
                        500, "TRANSCRIPTION_FAILED"));
                return;
            }

            // Parse and format the response
            JsonNode responseJson = objectMapper.readTree(dgResponse.body());
            Map<String, Object> formatted = formatResponse(responseJson, model);
            ctx.json(formatted);

        } catch (Exception e) {
            System.err.println("Transcription error: " + e.getMessage());
            ctx.status(500).json(formatError(
                    "An error occurred during transcription",
                    500, "TRANSCRIPTION_FAILED"));
        }
    }

    /**
     * GET /api/metadata
     *
     * Returns metadata about this starter application from deepgram.toml.
     * Required for standardization compliance.
     *
     * @param ctx Javalin request context
     */
    private static void handleMetadata(Context ctx) {
        try {
            TomlMapper tomlMapper = new TomlMapper();
            JsonNode tomlData = tomlMapper.readTree(
                    new java.io.File("deepgram.toml"));

            JsonNode meta = tomlData.get("meta");
            if (meta == null) {
                ctx.status(500).json(Map.of(
                        "error", "INTERNAL_SERVER_ERROR",
                        "message", "Missing [meta] section in deepgram.toml"
                ));
                return;
            }

            // Convert to a regular map for clean JSON output
            Map<String, Object> metaMap = objectMapper.treeToValue(meta, Map.class);
            ctx.json(metaMap);
        } catch (Exception e) {
            System.err.println("Error reading metadata: " + e.getMessage());
            ctx.status(500).json(Map.of(
                    "error", "INTERNAL_SERVER_ERROR",
                    "message", "Failed to read metadata from deepgram.toml"
            ));
        }
    }

    // ========================================================================
    // SECTION 10: SERVER START
    // ========================================================================

    /**
     * Application entry point. Loads configuration, validates the API key,
     * and starts the Javalin HTTP server.
     *
     * @param args Command-line arguments (unused)
     */
    public static void main(String[] args) {
        // Load API key (exits if missing)
        apiKey = loadApiKey();

        // Create Javalin app with CORS enabled
        Javalin app = Javalin.create(config -> {
            // Enable CORS -- wildcard is safe because same-origin is enforced
            // via Vite proxy / Caddy in production
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
        });

        // Session route (unprotected)
        app.get("/api/session", App::handleSession);

        // Metadata route (unprotected)
        app.get("/api/metadata", App::handleMetadata);

        // Health check route (unprotected)
        app.get("/health", ctx -> {
            ctx.json(Map.of("status", "ok"));
        });

        // Transcription route (protected by session auth)
        app.post("/api/transcription", ctx -> {
            if (requireSession(ctx)) return;
            handleTranscription(ctx);
        });

        // Start the server
        app.start(HOST, PORT);

        String separator = "=".repeat(70);
        System.out.println();
        System.out.println(separator);
        System.out.println("  Backend API running at http://localhost:" + PORT);
        System.out.println("  GET  /api/session");
        System.out.println("  POST /api/transcription (auth required)");
        System.out.println("  GET  /api/metadata");
        System.out.println("  GET  /health");
        System.out.println(separator);
        System.out.println();
    }
}
