import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONException;
import org.json.JSONObject;

public class Main {
    public static void main(String[] args) throws IOException {
        // Load environment variables from the .env file
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        // Get the Deepgram API access token from the environment variables
        String deepgramAccessToken = dotenv.get("deepgram_api_key");

        // Start the HTTP server on port 8080
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api", new MyHandler(deepgramAccessToken));
        server.setExecutor(null); // Use the default executor
        server.start();
        System.out.println("Server started on port 8080");
    }

    static class MyHandler implements HttpHandler {
        private final String deepgramAccessToken;

        public MyHandler(String deepgramAccessToken) {
            this.deepgramAccessToken = deepgramAccessToken;
        }

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            // Add CORS headers to allow requests from any origin
            httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            httpExchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            httpExchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization");

            System.out.println("Request: " + httpExchange.getRequestMethod() + " " + httpExchange.getRequestURI());
            if ("POST".equals(httpExchange.getRequestMethod())) {
                // Get the request body as an InputStream
                InputStream requestBody = httpExchange.getRequestBody();

                // Create a URL object with the updated endpoint
                URL url = new URL("https://api.deepgram.com/v1/listen");

                // Open a connection to the URL
                HttpURLConnection con = (HttpURLConnection) url.openConnection();

                // Set the request method to POST
                con.setRequestMethod("POST");

                // Set request headers
                con.setRequestProperty("accept", "application/json");
                con.setRequestProperty("content-type", "audio/wav"); // Assuming the audio is in WAV format
                con.setRequestProperty("Authorization", "Token " + deepgramAccessToken);

                // Enable output and input
                con.setDoOutput(true);
                con.setDoInput(true);

                // Copy the request body to the Deepgram API request
                try (OutputStream out = con.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = requestBody.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                // Get the response code
                int responseCode = con.getResponseCode();

                // Read the response
                InputStream responseStream = responseCode >= 400 ? con.getErrorStream() : con.getInputStream();
                ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; // Add this line to declare the buffer variable
                int bytesRead; // Add this line to declare the bytesRead variable
                while ((bytesRead = responseStream.read(buffer)) != -1) {
                    responseBytes.write(buffer, 0, bytesRead);
                }
                responseStream.close();

                // Convert the response to a JSONObject
                JSONObject jsonResponse;
                try {
                    jsonResponse = new JSONObject(responseBytes.toString());
                } catch (JSONException e) {
                    // Handle JSON parsing exception
                    e.printStackTrace();
                    return;
                }

                // Create the JSON response object
                JSONObject responseObject = new JSONObject();
                try {
                    responseObject.put("model", "general"); // Replace with the actual model used
                    responseObject.put("version", "1.0"); // Replace with the actual version used
                    responseObject.put("tier", "nova"); // Replace with the actual tier used
                    responseObject.put("dgFeatures", new JSONObject()); // Replace with the actual features used
                    responseObject.put("transcription", jsonResponse);
                } catch (JSONException e) {
                    // Handle JSON creation exception
                    e.printStackTrace();
                    return;
                }

                // Set the response headers
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(responseCode, responseObject.toString().length());

                // Write the response data
                try (OutputStream responseBody = httpExchange.getResponseBody()) {
                    responseBody.write(responseObject.toString().getBytes());
                }
            } else {
                // Method not allowed
                httpExchange.sendResponseHeaders(405, -1);
            }
        }
    }
}
