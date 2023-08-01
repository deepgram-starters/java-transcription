import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;

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

            // System.out.println("Request: " + httpExchange.getRequestMethod() + " " + httpExchange.getRequestURI());
            if ("POST".equals(httpExchange.getRequestMethod())) {
                // Get the request body as an InputStream
                InputStream requestBody = httpExchange.getRequestBody();
                BufferedReader reader = new BufferedReader(new InputStreamReader(requestBody));

                StringBuilder formData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    formData.append(line);
                }

                System.out.println("Request body: " + formData.toString());
                

                // Separate the form data parts
                String[] formDataParts = formData.toString().split("------WebKitFormBoundary");

                String audio_url = null;
                String model = null;
                String tier = null;
                JSONObject features = null;
                byte[] audioFile = null;

                for (int i = 1; i < formDataParts.length; i++) {
                    String formDataPart = formDataParts[i];

                    if (formDataPart.contains("name=\"url\"")) {
                        audio_url = extractValue(formDataPart);
                    } else if (formDataPart.contains("name=\"model\"")) {
                        model = extractValue(formDataPart);
                    } else if (formDataPart.contains("name=\"tier\"")) {
                        tier = extractValue(formDataPart);
                    } else if (formDataPart.contains("name=\"features\"")) {
                        try {
                            Map<String, Object> featuresData = extractFeaturesFromInputString(formDataPart);
                            features = convertMapToJson(featuresData);
                        } catch (JSONException e) {
                            // Handle JSON parsing exception
                            System.out.println("Error parsing features data");
                        }
                    } else if (formDataPart.contains("name=\"file\"")) {
                        try{
                        System.out.println("Audio file: " + formDataPart);
                        saveAudioToFile(formDataPart.getBytes(), "output.mp3");
                        audioFile = extractAudioData(formDataPart);
                        }
                        catch (Exception e) {
                            System.out.println("Error parsing audio file");
                        }
                    }
                }

                // Printing the form data to the console
                System.out.println("URL: " + audio_url);
                System.out.println("Model: " + model);
                System.out.println("Tier: " + tier);
                System.out.println("Features: " + features);
                try{
                System.out.println("Audio file: " + audioFile.toString().substring(0, 1000) + "...");
                } catch (Exception e) {
                    System.out.println("Error printing audio file");
                }

                // Create a URL object with the updated endpoint
                String requestUrl = "https://api.deepgram.com/v1/listen";
                StringBuilder urlParams = new StringBuilder();

                // Prepare the request body
                JSONObject requestBodyJson = new JSONObject();
                try{
                requestBodyJson.put("model", model);
                requestBodyJson.put("url", audio_url);
                requestBodyJson.put("tier", tier);
                // Add the "features" parameter directly to the request body
                if (features != null) {
                    Iterator<String> featuresKeys = features.keys();
                    while (featuresKeys.hasNext()) {
                        String key = featuresKeys.next();
                        Object value = features.get(key);
                        urlParams.append("&").append(key).append("=").append(value);
                    }
                }
                } catch (JSONException e) {
                    // Handle JSON parsing exception
                    System.out.println("Error parsing request body");
                }

                // Append the URL parameters to the request URL
                requestUrl += "?" + urlParams.toString();

                // Create a URL object with the updated endpoint
                URL url = new URL(requestUrl);

                System.out.println("Request body: " + requestBodyJson.toString());

                // Open a connection to the URL
                HttpURLConnection con = (HttpURLConnection) url.openConnection();

                // Set the request method to POST
                con.setRequestMethod("POST");

                // Set request headers
                con.setRequestProperty("accept", "application/json");
                con.setRequestProperty("Authorization", "Token " + deepgramAccessToken);

                // Enable output and input
                con.setDoOutput(true);
                con.setDoInput(true);

                // If audioFile is not null, send the audio data as bytes
                if ( audioFile != null) {
                        // Set the request content type to audio/wav or any other appropriate audio format
                        con.setRequestProperty("Content-Type", "audio/mp3");

                        try (OutputStream out = con.getOutputStream()) {
                            out.write(audioFile);
                        }
                } else {
                    // Otherwise, send the URL in the JSON body
                    con.setRequestProperty("content-Type", "application/json");
                    try (OutputStream out = con.getOutputStream()) {
                        out.write(requestBodyJson.toString().getBytes());
                    }
                }

                // Get the response code
                int responseCode = con.getResponseCode();

                // Read the response
                InputStream responseStream = responseCode >= 400 ? con.getErrorStream() : con.getInputStream();
                ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
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
                    responseObject.put("model", model); // Replace with the actual model used
                    responseObject.put("version", "1.0"); // Replace with the actual version used
                    responseObject.put("tier", tier); // Replace with the actual tier used
                    responseObject.put("dgFeatures", features); // Replace with the actual features used
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

        private static String extractValue(String formDataPart) {
            // Use a regex pattern to find the value after "name="
            Pattern pattern = Pattern.compile("name=\"(.*?)\"");
            Matcher matcher = pattern.matcher(formDataPart);

            // Check if the pattern is found
            if (matcher.find()) {
                // Extract the name from the first group
                String name = matcher.group(1);
                // Find the starting index of the value after the name
                int valueStartIndex = formDataPart.indexOf(name) + name.length() + 1; // Add 1 to skip the opening quotation mark
                // Find the ending index of the value (next quotation mark or end of line)
                int valueEndIndex = formDataPart.indexOf("\"", valueStartIndex);
                if (valueEndIndex == -1) {
                    valueEndIndex = formDataPart.length();
                }
                // Return the value by trimming the formDataPart from the valueStartIndex to valueEndIndex
                return formDataPart.substring(valueStartIndex, valueEndIndex).trim();
            }
            return null; // Return null if the pattern is not found
        }

        private byte[] extractAudioData(String formDataPart) throws IOException {
            // Use regex pattern to find the audio data in the form data part
            Pattern pattern = Pattern.compile("Content-Type: (.*)");
            Matcher matcher = pattern.matcher(formDataPart);

            Pattern pattern2 = Pattern.compile("Content-Type: audio/wav(.+)");
            Matcher matcher2 = pattern2.matcher(formDataPart);

            if (matcher2.find()) {
                String filename = matcher2.group(1);
                System.out.println("Filename: " + filename);
            }

            if (matcher.find()) {
                // Extract the audio data from the matched group
                String audioDataString = matcher.group(1);

                System.out.println("Audio data: " + audioDataString.length());

                // Convert audio data from Base64 string to byte array
                return audioDataString.getBytes();
            }

            return null;
        }

        public static Map<String, Object> extractFeaturesFromInputString(String input) throws JSONException {
            // Define the pattern to match the JSON part in the input string
            Pattern pattern = Pattern.compile("\\{.*\\}");
            Matcher matcher = pattern.matcher(input);

            if (matcher.find()) {
                String jsonString = matcher.group();
                JSONObject jsonObject = new JSONObject(jsonString);

                // Convert the JSON to a Java Map
                Map<String, Object> featuresMap = new HashMap<>();
                Iterator<String> keysIterator = jsonObject.keys();
                while (keysIterator.hasNext()) {
                    String key = keysIterator.next();
                    Object value = jsonObject.get(key);
                    featuresMap.put(key, value);
                }

                return featuresMap;
            } else {
                throw new JSONException("No JSON found in the input string.");
            }
        }

        // Function to save the audio data to a file
        private static void saveAudioToFile(byte[] audioData, String filename) {
            try (FileOutputStream outputStream = new FileOutputStream(filename)) {
                outputStream.write(audioData);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        public static JSONObject convertMapToJson(Map<String, Object> map) throws JSONException {
            JSONObject jsonObject = new JSONObject();

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                jsonObject.put(key, value);
            }

            return jsonObject;
        }
    }
}
