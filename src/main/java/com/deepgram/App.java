import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.cdimascio.dotenv.Dotenv;

public class App extends NanoHTTPD {

    public App(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            uri = "/index.html"; // Default to index.html if root is requested
        }
        String filePath = "./static" + uri;

        // Serve frontend files
        if (uri.endsWith(".html") || uri.endsWith(".css") || uri.endsWith(".js") || uri.endsWith(".svg")) {
            try {
                return newChunkedResponse(Response.Status.OK, getMimeTypeForFile(uri), getFileInputStream(filePath));
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File not found");
            }
        }

        // Handle backend API requests
        if (Method.POST.equals(session.getMethod()) && "/api".equals(uri)) {
            return handleApiRequest(session);
        }

        // For any other requests, return a 404 Not Found response
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found");
    }

    private Response handleApiRequest(IHTTPSession session) {
        // Your existing API handling code
        Method method = session.getMethod();
        String uri = session.getUri();
        String model = null;
        String tier = null;
        String url = null;
        String file = null;
        JSONObject features = null;
        File inputFile = null;
        byte[] audioBytes = null;

        if (Method.POST.equals(method) && "/api".equals(uri)) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);

                Map<String, String> params = session.getParms();

                try { session.parseBody(files); }
                catch (IOException e1) { e1.printStackTrace(); }
                catch (ResponseException e1) { e1.printStackTrace(); }

                model = params.get("model");
                tier = params.get("tier");
                url = params.get("url");
                file = params.get("file");
                try{
                features = new JSONObject(params.get("features"));
                } catch (JSONException err){
                    System.out.println("Error" + err.toString());
                }
                if (file!=null){
                inputFile = new File(files.get("file"));
                }
               
            } catch (IOException | ResponseException e) {
                e.printStackTrace();
            }
        }

        // Load environment variables from the .env file
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        // Get the Deepgram API access token from the environment variables
        String deepgramAccessToken = dotenv.get("deepgram_api_key");

        // Create a URL object with the updated endpoint
        String requestUrl = "https://api.deepgram.com/v1/listen";
        StringBuilder urlParams = new StringBuilder();

        // Prepare the request body
        JSONObject requestBodyJson = new JSONObject();
        try{
        requestBodyJson.put("model", model);
        if (url != null) {
            requestBodyJson.put("url", url);
        } 
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
        }
        catch (JSONException err){
            System.out.println("Error" + err.toString());
        }

        // Append the URL parameters to the request URL
        requestUrl += "?" + urlParams.toString();

        // Create a URL object with the updated endpoint
        try{
        URL Requrl = new URL(requestUrl);
        
        // Create a new HTTP connection
        HttpURLConnection con = (HttpURLConnection) Requrl.openConnection();

        // Set the request method to POST
        con.setRequestMethod("POST");

        // Set request headers
        con.setRequestProperty("accept", "application/json");
        con.setRequestProperty("Authorization", "Token " + deepgramAccessToken);

        // Enable output and input
        con.setDoOutput(true);
        con.setDoInput(true);

        // If inputFile is not null, send the audio data as bytes
        if (url == null) {
            // Set the request content type to audio/wav or any other appropriate audio format
            // Split the file name to find the audio extension, May have other .
            String[] fileSplit = file.split("\\.");

            // Set the request content type to audio/wav or any other appropriate audio format
            try (OutputStream out = con.getOutputStream()) {
                // read the file as bytes and write it to the output stream
                byte[] audioData = Files.readAllBytes(inputFile.toPath());
                out.write(audioData);
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
        System.out.println("Response code: " + responseCode);
        try {
            jsonResponse = new JSONObject(responseBytes.toString());
        } catch (JSONException e) {
            // Handle JSON parsing exception
            e.printStackTrace();
            return newFixedLengthResponse("Error: " + e.getMessage());
        }

        // Create the JSON response object
        JSONObject responseObject = new JSONObject();
        try {
            responseObject.put("model", model);
            responseObject.put("version", "1.0");
            responseObject.put("tier", tier);
            responseObject.put("dgFeatures", features);
            responseObject.put("transcription", jsonResponse);
        } catch (JSONException e) {
            e.printStackTrace();
            return newFixedLengthResponse("Error: " + e.getMessage());
        }

        // Set the response headers
        String responseString = responseObject.toString();
        Response reqResponse = newFixedLengthResponse(Response.Status.OK, "application/json", responseString);
        reqResponse.addHeader("Access-Control-Allow-Origin", "*"); // Replace "*" with the allowed origin
        reqResponse.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        reqResponse.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization");

        return reqResponse;
        }
        catch (IOException err){
            System.out.println("Error" + err.toString());
            return newFixedLengthResponse("Error: " + err.getMessage());
        }
    }

    private FileInputStream getFileInputStream(String filePath) throws FileNotFoundException {
        return new FileInputStream(filePath);
    }

    public static String getMimeTypeForFile(String uri) {
        if (uri.endsWith(".html")) {
            return "text/html";
        } else if (uri.endsWith(".css")) {
            return "text/css";
        } else if (uri.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (uri.endsWith(".js")) {
            return "application/javascript";
        } else {
            return NanoHTTPD.MIME_PLAINTEXT;
        }
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        int port = Integer.parseInt(dotenv.get("port"));
        App server = new App(port);
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            System.out.println("Server started on port " + port);
            // Keep the server running indefinitely
            while (true) {
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            server.stop();
            System.out.println("Server stopped.");
        }
    }

    private String getParam(Map<String, List<String>> params, String paramName) {
        List<String> values = params.get(paramName);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    public static byte[] readFileBytes(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        return Files.readAllBytes(path);
    }

}
