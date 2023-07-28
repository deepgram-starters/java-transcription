import fi.iki.elonen.NanoHTTPD;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class FrontendServer extends NanoHTTPD {

    public FrontendServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            uri = "/index.html"; // Default to index.html if root is requested
        }
        String filePath = "./build" + uri;

        try {
            return newChunkedResponse(Response.Status.OK, getMimeTypeForFile(uri), getFileInputStream(filePath));
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File not found");
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
    }
    else if (uri.endsWith(".js")) {
        return "application/javascript";
    } else {
        return NanoHTTPD.MIME_PLAINTEXT;
    }
    }

    public static void main(String[] args) {
        int port = 3000;
        FrontendServer server = new FrontendServer(port);
        try {
            server.start();
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
}
