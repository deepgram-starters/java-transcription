/**
 * Java Transcription Starter - Backend Server
 *
 * This is a lightweight Javalin server that provides a transcription API endpoint
 * powered by the Deepgram Java SDK. It accepts file uploads or URLs and returns
 * transcription results.
 *
 * Key Features:
 * - Single API endpoint: POST /api/transcription
 * - Accepts both file uploads and URLs
 * - CORS enabled for frontend communication
 * - JWT session auth with rate limiting (production only)
 * - Pure API server (frontend served separately)
 * - Uses Deepgram Java SDK for transcription
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
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import com.deepgram.DeepgramClient;
import com.deepgram.resources.listen.v1.media.requests.ListenV1RequestUrl;
import com.deepgram.resources.listen.v1.media.types.MediaTranscribeResponse;
import com.deepgram.types.ListenV1AcceptedResponse;
import com.deepgram.types.ListenV1Response;
import com.deepgram.types.ListenV1ResponseMetadata;
import com.deepgram.types.ListenV1ResponseResults;
import com.deepgram.types.ListenV1ResponseResultsChannelsItem;
import com.deepgram.types.ListenV1ResponseResultsChannelsItemAlternativesItem;
import com.deepgram.types.ListenV1ResponseResultsChannelsItemAlternativesItemWordsItem;

import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Shared Jackson ObjectMapper for JSON serialization.
     * The Jdk8Module is registered to support serialization of Java 8
     * Optional types used throughout the Deepgram SDK response objects.
     */
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module());

    // ========================================================================
    // SECTION 3: CONFIGURATION
    // ========================================================================

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

    /** The Deepgram SDK client instance */
    private static DeepgramClient dgClient;

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
    // SECTION 6: HELPER FUNCTIONS
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

    /**
     * Formats the SDK response into a simplified, consistent structure
     * that conforms to the starter contract schema.
     *
     * Contract schema (interfaces/transcription/schema/transcript.json):
     * - transcript: string (required)
     * - words: array of { text, start, end } objects (optional)
     * - duration: number in seconds (optional)
     * - metadata: object with additional info (optional)
     *
     * Note: The SDK returns word items with a "word" field, but the
     * contract schema expects "text", so we map between them here.
     *
     * @param response The Deepgram ListenV1Response from the SDK
     * @return A formatted response map conforming to the contract schema
     */
    private static Map<String, Object> formatResponse(ListenV1Response response) {
        Map<String, Object> result = new LinkedHashMap<>();

        ListenV1ResponseResults results = response.getResults();
        ListenV1ResponseMetadata metadata = response.getMetadata();

        // Navigate: results -> channels[0] -> alternatives[0]
        String transcript = "";
        List<Map<String, Object>> words = new ArrayList<>();
        if (results != null && !results.getChannels().isEmpty()) {
            ListenV1ResponseResultsChannelsItem channel = results.getChannels().get(0);
            Optional<List<ListenV1ResponseResultsChannelsItemAlternativesItem>> alts =
                    channel.getAlternatives();
            if (alts.isPresent() && !alts.get().isEmpty()) {
                ListenV1ResponseResultsChannelsItemAlternativesItem alt = alts.get().get(0);
                transcript = alt.getTranscript().orElse("");

                // Map SDK word items to contract schema format:
                // SDK: { word, start, end, confidence }
                // Contract: { text, start, end }
                List<ListenV1ResponseResultsChannelsItemAlternativesItemWordsItem> sdkWords =
                        alt.getWords().orElse(Collections.emptyList());
                for (ListenV1ResponseResultsChannelsItemAlternativesItemWordsItem w : sdkWords) {
                    Map<String, Object> wordMap = new LinkedHashMap<>();
                    wordMap.put("text", w.getWord().orElse(""));
                    wordMap.put("start", w.getStart().orElse(0f));
                    wordMap.put("end", w.getEnd().orElse(0f));
                    words.add(wordMap);
                }
            }
        }

        result.put("transcript", transcript);
        result.put("words", words);

        // Add duration from metadata if available
        if (metadata != null) {
            // Build metadata object
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("request_id", objectMapper.convertValue(
                    metadata.getRequestId(), Object.class));
            result.put("metadata", meta);
        }

        return result;
    }

    // ========================================================================
    // SECTION 7: SESSION ROUTES
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
    // SECTION 8: API ROUTES
    // ========================================================================

    /**
     * POST /api/transcription
     *
     * Main transcription endpoint. Accepts either:
     * - A file upload (multipart/form-data with 'file' field)
     * - A URL to audio (form field 'url')
     *
     * Uses the Deepgram Java SDK for transcription. The SDK handles
     * authentication, request formatting, and response parsing.
     *
     * The response is a union type (MediaTranscribeResponse) which is
     * either a ListenV1Response (immediate result) or a
     * ListenV1AcceptedResponse (callback mode). The visitor pattern
     * is used to handle both cases.
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

            // Validate input -- must have either file or URL
            if ((file == null) && (url == null || url.trim().isEmpty())) {
                ctx.status(400).json(formatError(
                        "Either file or url must be provided", 400, "MISSING_INPUT"));
                return;
            }

            MediaTranscribeResponse result;

            if (url != null && !url.trim().isEmpty()) {
                // URL-based transcription via SDK
                result = dgClient.listen().v1().media().transcribeUrl(
                        ListenV1RequestUrl.builder()
                                .url(url.trim())
                                .build()
                );
            } else {
                // File-based transcription via SDK
                byte[] fileBytes;
                try (InputStream is = file.content()) {
                    fileBytes = is.readAllBytes();
                }
                result = dgClient.listen().v1().media().transcribeFile(fileBytes);
            }

            // Extract the response using the visitor pattern.
            // MediaTranscribeResponse is a union type: either a ListenV1Response
            // (with immediate transcription results) or a ListenV1AcceptedResponse
            // (when using callback mode).
            Map<String, Object> response = result.visit(
                    new MediaTranscribeResponse.Visitor<Map<String, Object>>() {
                        @Override
                        public Map<String, Object> visit(ListenV1Response value) {
                            return formatResponse(value);
                        }

                        @Override
                        public Map<String, Object> visit(ListenV1AcceptedResponse value) {
                            Map<String, Object> accepted = new LinkedHashMap<>();
                            accepted.put("status", "accepted");
                            accepted.put("request_id", value.getRequestId());
                            return accepted;
                        }
                    }
            );

            ctx.json(response);

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
    // SECTION 9: SERVER START
    // ========================================================================

    /**
     * Application entry point. Loads configuration, validates the API key,
     * initializes the Deepgram SDK client, and starts the Javalin HTTP server.
     *
     * @param args Command-line arguments (unused)
     */
    public static void main(String[] args) {
        // Load API key (exits if missing)
        apiKey = loadApiKey();

        // Initialize Deepgram SDK client
        dgClient = DeepgramClient.builder()
                .apiKey(apiKey)
                .build();

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
