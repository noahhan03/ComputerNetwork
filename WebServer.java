import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WebServer {
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

    public WebServer(int portNum, boolean cookieEnabled) throws IOException {
        this.port = portNum;
        serverSocket = new ServerSocket(port);
        this.cookieFeature = cookieEnabled;
    }

    public void start() {
        System.out.println("Listening on port: " + this.port);
        // 항상 열린 거 구현
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                InputStream input = clientSocket.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                String line = reader.readLine();
                if (line != null) {
                    // request 화면에 출력
                    System.out.println("Request: " + line);

                    // header 처리
                    Map<String, String> headers = new HashMap<>();
                    String headerLine;
                    while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                        int colonIndex = headerLine.indexOf(":");
                        if (colonIndex != -1) {
                            String headerName = headerLine.substring(0, colonIndex).trim();
                            String headerValue = headerLine.substring(colonIndex + 1).trim();
                            headers.put(headerName, headerValue);
                        }
                    }
                    String UserCookie = getUserCookie(headers);
                    // get 요청 처리
                    if (line.startsWith("GET")) {
                        String[] parts = line.split(" ");
                        String Path = parts[1];
                        if (Path.equals("/")) {
                            System.out.println("Idx page request");
                            if (cookieFeature && UserData.containsKey(UserCookie)) {
                                String lastLoc = UserData.get(UserCookie);
                                // 로그 출력
                                System.out.println("Returning user. Redirecting to: " + lastLoc);
                                String path = "/" + lastLoc;
                                System.out.println("HTTP GET: /index.html");
                                Redirect(clientSocket, path);
                            } else {
                                System.out.println("GET: /index.html");
                                sendResponse(clientSocket, "index.html", "text/html", UserCookie, headers);
                            }
                        } else if (Path.equals("/mountains") || Path.equals("/city") || Path.equals("/beach")) {
                            String dest = Path.split("/")[1];
                            System.out.println("dest page request");
                            System.out.println("GET: /" + dest);
                            if (cookieFeature)
                                UserData.put(UserCookie, dest);
                            sendDestResponse(clientSocket, dest, UserCookie, headers);
                        } else if (Path.startsWith("/pictures")) {
                            String path = Path.split("/")[2];
                            System.out.println("HTTP GET: /pictures/" + path);
                            sendImgResponse(clientSocket, path, UserCookie, headers);
                        } else {
                            // detail 페이지 확인
                            String dest = Path.split("/")[1].replace("-", " ");
                            if (destExit(dest)) {
                                if (cookieFeature)
                                    UserData.put(UserCookie, dest.replace(" ", "-"));
                                System.out.println("Detail page requested");
                                System.out.println("HTTP GET: /" + dest);
                                sendDetail(clientSocket, dest, UserCookie, headers);
                            } else {
                                send404(clientSocket, UserCookie);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error handling client connection: " + e.getMessage());
            }
        }
    }

    private String getUserCookie(Map<String, String> headers) {
        String cook = null;
        if (cookieFeature == false)
            return null;
        String cookieHeader = headers.get("Cookie");
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split("; ");
            for (String cookie : cookies) {
                if (cookie.startsWith("UserId=")) {
                    cook = cookie.substring(7);
                    System.out.println("cookie : " + cook);
                }
            }
        }

        if (cook == null) {
            cook = String.valueOf((long) (Math.random() * 1000000000L));
            System.out.println("New user requested page, cookie will be set.");
        } else {
            System.out.println("Returning user, UserId: " + cook);
        }

        return cook;
    }

    private void sendResponse(Socket clientSocket, String fileName, String contentType, String cookie,
            Map<String, String> headers)
            throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/" + fileName;
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        String ifModifiedSince = headers.get("If-Modified-Since");
        if (ifModifiedSince != null && lastModified != null && isNotModified(ifModifiedSince, lastModified)) {
            sendNotModifiedResponse(O);
            clientSocket.close();
            return;
        }

        if (file.exists()) {

            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    (cookieFeature ? "Set-Cookie: UserId=" + cookie + "\r\n" : "") +
                    (cookieFeature ? "Cache-Control: no-store\r\n" : "Cache-Control: max-age=60\r\n") +
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

    private void sendDestResponse(Socket clientSocket, String dest, String cook, Map<String, String> headers)
            throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/destination.html";
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        String Dest1 = "", Dest2 = "";
        // modified;
        String ifModifiedSince = headers.get("If-Modified-Since");
        if (ifModifiedSince != null && lastModified != null && isNotModified(ifModifiedSince, lastModified)) {
            sendNotModifiedResponse(O);
            clientSocket.close();
            return;
        }
        if (file.exists()) {
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
                    (cookieFeature ? "Cache-Control: no-store\r\n" : "Cache-Control: max-age=60\r\n") +
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

    private void sendImgResponse(Socket clientSocket, String path, String userId, Map<String, String> headers)
            throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/pictures/" + path;
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        String ifModifiedSince = headers.get("If-Modified-Since");
        if (ifModifiedSince != null && lastModified != null && isNotModified(ifModifiedSince, lastModified)) {
            sendNotModifiedResponse(O);
            clientSocket.close();
            return;
        }

        if (file.exists()) {

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

    private boolean destExit(String dest) {
        try {
            // String String = new
            // String(Files.readAllBytes(Paths.get("resources/destinations.json")));
            // JsonObject Object = JsonParser.parseString(String).getAsJsonObject();
            JsonObject Object = getDestinationsData();
            JsonArray Array = Object.getAsJsonArray("destinations");
            for (JsonElement element : Array) {
                JsonObject destObject = element.getAsJsonObject();
                String name = destObject.get("name").getAsString();
                if (name.equalsIgnoreCase(dest)) {
                    return true;
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading JSON file: " + e.getMessage());
        }
        return false;
    }

    private void sendDetail(Socket clientSocket, String dest, String cookie, Map<String, String> headers)
            throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        String filePath = "resources/detail.html";
        File file = new File(filePath);
        String lastModified = getLastModifiedTime(filePath);
        // modified;
        String ifModifiedSince = headers.get("If-Modified-Since");
        if (ifModifiedSince != null && lastModified != null && isNotModified(ifModifiedSince, lastModified)) {
            sendNotModifiedResponse(O);
            clientSocket.close();
            return;
        }
        if (file.exists()) {

            JsonObject Obj = getDestinationsData();
            JsonArray Arr = Obj.getAsJsonArray("destinations");
            String type = "", description = "", img = "";
            for (JsonElement element : Arr) {
                JsonObject destObject = element.getAsJsonObject();

                if (destObject.get("name").getAsString().equals(dest)) {
                    type = destObject.get("type").getAsString();
                    description = destObject.get("description").getAsString();
                    // System.out.println("detail , descrp : "+description);
                    img = destObject.get("image").getAsString().replace("//", "/");
                }

            }
            String HTMLstring = new String(Files.readAllBytes(Paths.get("resources/detail.html")));
            HTMLstring = HTMLstring.replace("TRAVEL DESTINATION TITLE", dest);
            HTMLstring = HTMLstring.replace("TRAVEL DESTINATION TYPE", type);
            HTMLstring = HTMLstring.replace("TRAVEL DESTINATION DESCRIPTION", description);
            HTMLstring = HTMLstring.replace("IMAGE SRC", img);

            String responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + HTMLstring.length() + "\r\n" +
                    (cookieFeature ? "Set-Cookie: UserId=" + cookie + "\r\n" : "") +
                    (cookieFeature ? "Cache-Control: no-store\r\n" : "Cache-Control: max-age=60\r\n") +
                    // "Date: " + getCurrentTime() + "\r\n" +
                    (lastModified != null ? "Last-Modified: " + lastModified + "\r\n" : "") +
                    "\r\n";
            O.write(responseHeader.getBytes());

            O.write(HTMLstring.getBytes());
            System.out.println("HTTP 200 OK /" + dest);
            clientSocket.close();
        } else
            send404(clientSocket, cookie);
    }

    private void Redirect(Socket clientSocket, String lastLoc) throws IOException {
        OutputStream O = clientSocket.getOutputStream();
        if (!lastLoc.startsWith("/")) {
            lastLoc = "/" + lastLoc;
        }
        String responseHeader = "HTTP/1.1 302 Found\r\n" +
                "Location: " + lastLoc + "\r\n" +
                "\r\n";

        System.out.println("HTTP 302 Found " + lastLoc);
        O.write(responseHeader.getBytes());
        clientSocket.close();
    }

    private boolean isNotModified(String ifModifiedSince, String lastModified) {
        try {
            SimpleDateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date ifModifiedSinceDate = formatter.parse(ifModifiedSince);
            Date lastModifiedDate = formatter.parse(lastModified);
            return !lastModifiedDate.after(ifModifiedSinceDate);
        } catch (ParseException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void send404(Socket clientSocket, String cookie) throws IOException {
        OutputStream O = clientSocket.getOutputStream();

        String responseHeader = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/html\r\n" +
                (cookieFeature ? "Set-Cookie: UserId=" + cookie + "\r\n" : "") +
                "\r\n";
        O.write(responseHeader.getBytes());
        System.out.println("HTTP 404 Not Found");
        clientSocket.close();
    }

    private static void sendNotModifiedResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 304 Not Modified\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
        System.out.println("[WebServer] Resource not modified.");
    }

    public static String getLastModifiedTime(String filePath) {
        try {
            Path path = Paths.get(filePath);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime fileTime = attrs.lastModifiedTime();

            // HTTP 헤더용 날짜 형식으로 변환
            SimpleDateFormat httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
            httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            return httpDateFormat.format(fileTime.toMillis());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        // 포트 번호 추출
        int portNum = 8080;
        try {
            portNum = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[0]);
            return;
        }
        // 쿠키 설정
        boolean cookieOpt = true;
        // 커맨드 라인 인자 처리
        if (args.length == 0) {
            try {
                WebServer server = new WebServer(8080, cookieOpt);
                server.start();
            } catch (IOException e) {
                System.err.println("Server failed to start: " + e.getMessage());
            }
        } else {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--cookie=off")) {
                    cookieOpt = false;
                } else if (args[i].equals("--cookie=on")) {
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
}