import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Web {
    private HashMap<String, String> UserData = new HashMap<>();
    private boolean cookieFeature = true;
    private ServerSocket serverSocket;
    private int port;
    private JsonObject destinationsJsonData = null;

    private JsonObject getDestinationsData() throws IOException {
        if (destinationsJsonData == null) {
            String str = new String(Files.readAllBytes(Paths.get("resources/destinations.json")));
            destinationsJsonData = JsonParser.parseString(str).getAsJsonObject();
        }
        return destinationsJsonData;
    }

    public Web(int portNum, boolean cookieEnabled) throws IOException {
        this.port = portNum;
        serverSocket = new ServerSocket(port);
        this.cookieFeature = cookieEnabled;
    }

    public void start() {
        System.out.println("Listening on port: " + this.port);
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                InputStream input = clientSocket.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));

                // Parse request line and headers
                String requestLine = reader.readLine();
                if (requestLine != null && requestLine.startsWith("GET")) {
                    System.out.println("Request: " + requestLine);

                    Map<String, String> headers = parseHeaders(reader);
                    String ifModifiedSince = headers.get("if-modified-since");
                    String connectionHeader = headers.get("connection");

                    String[] parts = requestLine.split(" ");
                    String path = parts[1];

                    // Process the request based on the path
                    if (path.equals("/")) {
                        handleIndexRequest(clientSocket, headers);
                    } else if (path.equals("/mountains") || path.equals("/city") || path.equals("/beach")) {
                        handleDestRequest(clientSocket, path.substring(1), headers);
                    } else if (path.startsWith("/pictures")) {
                        handleImageRequest(clientSocket, path.split("/")[2], headers);
                    } else {
                        send404(clientSocket, "");
                    }

                    // Check Connection header
                    if ("close".equalsIgnoreCase(connectionHeader)) {
                        System.out.println("Connection: close detected. Closing client connection.");
                        clientSocket.close();
                    }
                }
            } catch (IOException e) {
                System.err.println("Error handling client connection: " + e.getMessage());
            }
        }
    }

    private void handleIndexRequest(Socket clientSocket, Map<String, String> headers) throws IOException {
        String userCookie = getUserCookie(headers);
        if (cookieFeature && UserData.containsKey(userCookie)) {
            String lastLoc = UserData.get(userCookie);
            Redirect(clientSocket, "/" + lastLoc);
        } else {
            sendResponse(clientSocket, "index.html", "text/html", userCookie);
        }
    }

    private void sendResponse(Socket clientSocket, String fileName, String contentType, String cookie)
            throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/" + fileName;
        File file = new File("resources/" + fileName);
        String lastModified = getLastModifiedTime(filePath);
        boolean flag = true;

        if (flag == true && file.exists()) {

            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    (cookieFeature ? "Set-Cookie: UserId=" + cookie + "\r\n" : "") +
                    // "Date: " + getCurrentTime() + "\r\n" +
                    (lastModified != null ? "Last-Modified: " + lastModified + "\r\n" : "") +
                    "\r\n";
            // 로그 출력
            System.out.println("Response Header:\n" + responseHeader);
            O.write(responseHeader.getBytes());

            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                O.write(buffer, 0, bytesRead);
            }
            fileInputStream.close();
            System.out.println("HTTP 200 OK: " + fileName);
            clientSocket.close();
        } else
            send404(clientSocket, cookie);
    }

    private void handleDestRequest(Socket clientSocket, String dest, Map<String, String> headers) throws IOException {
        sendDestResponse(clientSocket, dest, getUserCookie(headers));
    }

    private void sendDestResponse(Socket clientSocket, String dest, String cook) throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/destination.html";
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        String Dest1 = "", Dest2 = "";
        boolean flag = true;

        if (flag == true && file.exists()) {
            if (dest.equals("mountains")) {
                Dest1 = "Swiss Alps";
                Dest2 = "Rocky Mountains";
            } else if (dest.equals("city")) {
                Dest1 = "Paris";
                Dest2 = "New York";
            } else {
                Dest1 = "Hawaii";
                Dest2 = "Maldives";
            }
            // destination.html 내용을 문자열로 읽고 대체할 텍스트 수정
            String HTMLstring = new String(Files.readAllBytes(Paths.get("resources/destination.html")));
            HTMLstring = HTMLstring.replace("TRAVEL DESTINATION TITLE 1", Dest1);
            HTMLstring = HTMLstring.replace("TRAVEL DESTINATION TITLE 2", Dest2);
            HTMLstring = HTMLstring.replace("/destination1", "/" + Dest1.replace(" ", "-"));
            HTMLstring = HTMLstring.replace("/destination2", "/" + Dest2.replace(" ", "-"));

            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + HTMLstring.length() + "\r\n" +
                    (cookieFeature ? "Set-Cookie: UserId=" + cook + "\r\n" : "") +
                    // "Date: " + getCurrentTime() + "\r\n" +
                    (lastModified != null ? "Last-Modified: " + lastModified + "\r\n" : "") +
                    "\r\n";
            O.write(responseHeader.getBytes());

            O.write(HTMLstring.getBytes());
            System.out.println("HTTP 200 OK /" + dest);
            clientSocket.close();
        } else
            send404(clientSocket, cook);
    }

    private void handleImageRequest(Socket clientSocket, String path, Map<String, String> headers) throws IOException {
        sendImgResponse(clientSocket, path, getUserCookie(headers));
    }

    private void sendImgResponse(Socket clientSocket, String path, String userId) throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/pictures/" + path;
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        // modified;
        boolean flag = true;

        if (flag == true && file.exists()) {

            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + file.length() + "\r\n" +
                    (cookieFeature ? "Set-Cookie: UserId=" + userId + "\r\n" : "") +
                    // "Date: " + getCurrentTime() + "\r\n" +
                    (lastModified != null ? "Last-Modified: " + lastModified + "\r\n" : "") +
                    "\r\n";
            O.write(responseHeader.getBytes());

            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                O.write(buffer, 0, bytesRead);
            }
            fileInputStream.close();
            System.out.println("HTTP 200 OK /pictures/" + path);
            clientSocket.close();
        } else
            send404(clientSocket, userId);
    }

    private static Map<String, String> parseHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIndex = line.indexOf(":");
            if (colonIndex != -1) {
                String headerName = line.substring(0, colonIndex).trim().toLowerCase();
                String headerValue = line.substring(colonIndex + 1).trim();
                headers.put(headerName, headerValue);
            }
        }
        return headers;
    }

    private String getUserCookie(Map<String, String> headers) {
        String cookieHeader = headers.get("cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                if (cookie.trim().startsWith("UserId=")) {
                    return cookie.trim().substring(7);
                }
            }
        }
        return String.valueOf((long) (Math.random() * 1000000000L));
    }

    private void Redirect(Socket clientSocket, String lastLoc) throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String responseHeader = "HTTP/1.1 302 Found\r\n" +
                "Location: " + lastLoc + "\r\n" +
                "\r\n";
        O.write(responseHeader.getBytes());
        clientSocket.close();
    }

    private void send404(Socket clientSocket, String cookie) throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String responseHeader = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/html\r\n" +
                (cookieFeature ? "Set-Cookie: UserId=" + cookie + "\r\n" : "") +
                "\r\n";
        O.write(responseHeader.getBytes());
        clientSocket.close();
    }

    private static void sendNotModifiedResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 304 Not Modified\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }

    public static String getLastModifiedTime(String filePath) {
        try {
            Path path = Paths.get(filePath);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime fileTime = attrs.lastModifiedTime();

            SimpleDateFormat httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            return httpDateFormat.format(fileTime.toMillis());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        int portNum = 8080;
        try {
            portNum = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[0]);
            return;
        }

        boolean cookieOpt = true;
        for (String arg : args) {
            if (arg.equals("--cookie=off")) {
                cookieOpt = false;
            } else if (arg.equals("--cookie=on")) {
                cookieOpt = true;
            }
        }

        try {
            WebServer server = new WebServer(portNum, cookieOpt);
            server.start();
        } catch (IOException e) {
            System.err.println("Server failed to start: " + e.getMessage());
        }
    }
}
