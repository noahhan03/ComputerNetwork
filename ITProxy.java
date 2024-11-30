import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

public class ITProxy {
    private static final int PORT = 8085;
    private static HashMap<String, CachedResource> cache = new HashMap<>();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Proxy server listening on port: " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());

            e.printStackTrace();
            // sendErrorResponse(out); // 500 오류 응답 전송
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new java.io.InputStreamReader(clientSocket.getInputStream()));
                OutputStream out = clientSocket.getOutputStream()) {

            // 클라이언트의 요청 라인 읽기
            String requestLine = in.readLine();
            System.out.println("[DEBUG] Request line: " + requestLine);

            if (requestLine != null && requestLine.startsWith("GET")) {
                String[] requestParts = requestLine.split(" ");
                String requestPath = requestParts[1];
                System.out.println("[DEBUG] Parsed request path: " + requestPath);

                // 프록시 서버로 받은 요청을 웹 서버로 전달
                forwardRequestToServer(clientSocket, requestLine, "localhost", 8080); // 8080번 포트에 웹 서버 연결

            } else {
                sendNotFoundResponse(out); // GET 요청이 아니면 404 반환
            }

        } catch (IOException e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendResponse(OutputStream out, String filePath, String contentType, boolean isCacheable)
            throws IOException {

        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            String lastModified = "Tue, 8 Oct 2024 23:07:19 +0900"; // Example timestamp for last modification
            String cacheControl = isCacheable ? "Cache-Control: max-age=60" : "Cache-Control: no-store";

            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "Last-Modified: " + lastModified + "\r\n" +
                    cacheControl + "\r\n" +
                    "\r\n";

            out.write(header.getBytes());
            out.write(content.getBytes());
            out.flush();
            System.out.println("[DEBUG] Sending " + filePath);
        } catch (Exception e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
            e.printStackTrace(); // 오류 메시지 출력
            sendErrorResponse(out); // 500 오류 응답 전송
        }

    }

    private static void sendImageResponse(OutputStream out, String imagePath) throws IOException {
        try {
            byte[] imageBytes = Files.readAllBytes(Paths.get("resources/" + imagePath));
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + imageBytes.length + "\r\n" +
                    "\r\n";

            out.write(header.getBytes());
            out.write(imageBytes);
            out.flush();
            System.out.println("[DEBUG] Sending image: " + imagePath);
        } catch (IOException e) {
            System.out.println("[DEBUG] Image not found: " + imagePath);
            sendNotFoundResponse(out);
        }
    }

    private static void sendDestinationResponse(OutputStream out, String type) throws IOException {

        try {
            String content = getDestinationPage(type);
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n";

            out.write(header.getBytes());
            out.write(content.getBytes());
            out.flush();
            System.out.println("[DEBUG] Sending " + type + " destination page.");
        } catch (Exception e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
            e.printStackTrace(); // 오류 메시지 출력
            sendErrorResponse(out); // 500 오류 응답 전송
        }
    }

    private static String getDestinationPage(String type) {
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get("resources/destinations.json")));
            JsonObject jsonObject = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonArray destinationsArray = jsonObject.getAsJsonArray("destinations");

            StringBuilder destinationTitles = new StringBuilder();
            for (JsonElement element : destinationsArray) {
                JsonObject destination = element.getAsJsonObject();
                if (destination.get("type").getAsString().equalsIgnoreCase(type)) {
                    destinationTitles.append(destination.get("name").getAsString()).append("\n");
                }
            }

            return destinationTitles.toString();

        } catch (IOException e) {
            e.printStackTrace();
            return "Error loading destination page";
        }
    }

    private static void sendDetailResponse(OutputStream out, String destinationName) throws IOException {
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get("resources/destinations.json")));
            JsonObject jsonObject = JsonParser.parseString(jsonContent).getAsJsonObject();
            JsonArray destinationsArray = jsonObject.getAsJsonArray("destinations");

            String description = "", image = "";
            for (JsonElement element : destinationsArray) {
                JsonObject destination = element.getAsJsonObject();
                if (destination.get("name").getAsString().equalsIgnoreCase(destinationName)) {
                    description = destination.get("description").getAsString();
                    image = destination.get("image").getAsString();
                    break;
                }
            }

            String htmlContent = new String(Files.readAllBytes(Paths.get("resources/detail.html")));
            htmlContent = htmlContent.replace("TRAVEL DESTINATION TITLE", destinationName);
            htmlContent = htmlContent.replace("TRAVEL DESTINATION DESCRIPTION", description);
            String[] imageParts = image.split("//");
            htmlContent = htmlContent.replace("IMAGE SRC", "pictures/" + imageParts[1]);

            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + htmlContent.length() + "\r\n" +
                    "\r\n";

            out.write(header.getBytes());
            out.write(htmlContent.getBytes());
            out.flush();
            System.out.println("[DEBUG] Sending detail page for: " + destinationName);

        } catch (IOException e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
            e.printStackTrace();
            sendErrorResponse(out); // 500 오류 응답 전송
        }
    }

    private static void sendNotFoundResponse(OutputStream out) throws IOException {

        try {
            String content = "<h1>404 Not Found</h1><p>The requested resource was not found on this server.</p>";
            String header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n";

            out.write(header.getBytes());
            out.write(content.getBytes());
            out.flush();
            System.out.println("[DEBUG] Sent 404 Not Found page.");
        } catch (Exception e) {
            System.err.println("[ERROR] Exception: " + e.getMessage());
            e.printStackTrace(); // 오류 메시지 출력
            sendErrorResponse(out); // 500 오류 응답 전송
        }

    }

    private static void sendErrorResponse(OutputStream out) throws IOException {
        String content = "<h1>500 Internal Server Error</h1><p>There was a problem processing your request.</p>";
        String header = "HTTP/1.1 500 Internal Server Error\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + content.length() + "\r\n" +
                "\r\n";

        out.write(header.getBytes());
        out.write(content.getBytes());
        out.flush();
        System.out.println("[DEBUG] Sent 500 Internal Server Error page.");
    }

    private static void forwardRequestToServer(Socket clientSocket, String requestLine, String serverHost,
            int serverPort) {
        try {
            // 서버에 요청을 전달하기 위한 연결
            Socket serverSocket = new Socket(serverHost, serverPort);
            OutputStream serverOut = serverSocket.getOutputStream();
            InputStream serverIn = serverSocket.getInputStream();

            // 요청을 서버로 전달
            serverOut.write(requestLine.getBytes());
            serverOut.flush();

            // 서버 응답을 클라이언트로 전달
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = serverIn.read(buffer)) != -1) {
                clientSocket.getOutputStream().write(buffer, 0, bytesRead);
            }
            clientSocket.getOutputStream().flush();

            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Cache structure to store cached resources
    private static class CachedResource {
        String content;
        String lastModified;
        boolean isCacheable;
    }
}
