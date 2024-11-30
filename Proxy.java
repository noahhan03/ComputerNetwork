import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;

public class Proxy {

    private static final int PROXY_PORT = 8085;
    private static final String WEB_SERVER_HOST = "localhost";
    private static final int WEB_SERVER_PORT = 8080;
    private static final int MAXAGE = 60;

    // Cache storage
    private static final Map<String, CachedPage> cache = new HashMap<>();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PROXY_PORT)) {
            System.out.println("Proxy Server running on port " + PROXY_PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClientRequest(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClientRequest(Socket clientSocket) {
        try (
                BufferedReader clientIn = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                OutputStream clientOut = clientSocket.getOutputStream()) {
            String requestLine = clientIn.readLine();
            if (requestLine == null || !requestLine.startsWith("GET")) {
                sendErrorResponse(clientOut, 400, "Bad Request");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            String url = requestParts[1];
            System.out.println("[Proxy] Received request for: " + url);

            if (cache.containsKey(url)) {
                CachedPage cachedPage = cache.get(url);
                long currentTime = System.currentTimeMillis();
                long maxAgeMillis = MAXAGE * 1000;
                if (currentTime - cachedPage.timestamp < maxAgeMillis) {
                    System.out.println("[Proxy] Cache is valid for: " + url);

                    String ifModifiedSince = cachedPage.lastModified;
                    boolean isModified = forwardRequestToServer(clientSocket, url, ifModifiedSince);

                    if (!isModified) {
                        System.out.println("[Proxy] Resource not modified. Serving cached page for: " + url);
                        sendCachedResponse(clientOut, cachedPage);
                    } else {
                        System.out.println("[Proxy] Resource modified. Updating cache for: " + url);
                        CachedPage updatedPage = fetchAndCachePage(url);
                        sendCachedResponse(clientOut, updatedPage);
                    }
                } else {
                    System.out.println("[Proxy] Cache expired for: " + url);
                    CachedPage updatedPage = fetchAndCachePage(url);
                    sendCachedResponse(clientOut, updatedPage);
                }
            } else {
                System.out.println("[Proxy] Cache miss for: " + url);
                CachedPage newPage = fetchAndCachePage(url);
                sendCachedResponse(clientOut, newPage);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sendErrorResponse(OutputStream clientOut, int statusCode, String message) throws IOException {
        String response = "HTTP/1.1 " + statusCode + " " + message + "\r\n\r\n";
        clientOut.write(response.getBytes());
    }

    private static void sendCachedResponse(OutputStream clientOut, CachedPage cachedPage) throws IOException {
        System.out.println("[Proxy] Serving cached page");
        String headersWithCache = addCacheHeaders(cachedPage.headers);
        clientOut.write(headersWithCache.getBytes());
        clientOut.write(cachedPage.body);
    }

    private static CachedPage fetchAndCachePage(String url) {
        try (Socket serverSocket = new Socket(WEB_SERVER_HOST, WEB_SERVER_PORT);
                BufferedReader serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                OutputStream serverOut = serverSocket.getOutputStream()) {

            String request = "GET " + url + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            serverOut.write(request.getBytes());
            serverOut.flush();

            StringBuilder responseHeaders = new StringBuilder();
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

            String line;
            boolean headersEnded = false;
            while ((line = serverIn.readLine()) != null) {
                if (!headersEnded) {
                    responseHeaders.append(line).append("\r\n");
                    if (line.isEmpty()) {
                        headersEnded = true;
                    }
                } else {
                    responseBody.write(line.getBytes());
                }
            }

            String lastModified = extractHeader(responseHeaders.toString(), "Last-Modified");
            CachedPage newPage = new CachedPage(responseHeaders.toString(), responseBody.toByteArray(), lastModified);

            cache.put(url, newPage);
            return newPage;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean forwardRequestToServer(Socket clientSocket, String url, String ifModifiedSince) {
        try (Socket serverSocket = new Socket(WEB_SERVER_HOST, WEB_SERVER_PORT);
                BufferedReader serverIn = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                OutputStream serverOut = serverSocket.getOutputStream();
                OutputStream clientOut = clientSocket.getOutputStream()) {

            StringBuilder request = new StringBuilder();
            request.append("GET ").append(url).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(WEB_SERVER_HOST).append("\r\n");
            if (ifModifiedSince != null) {
                request.append("If-Modified-Since: ").append(ifModifiedSince).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            serverOut.write(request.toString().getBytes());
            serverOut.flush();

            String responseLine = serverIn.readLine();
            System.out.println("[Proxy] Received response from WebServer: " + responseLine);

            if (responseLine.startsWith("HTTP/1.1 304 Not Modified")) {
                return false;
            }

            StringBuilder responseHeaders = new StringBuilder();
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

            boolean headersEnded = false;
            String line;
            while ((line = serverIn.readLine()) != null) {
                if (!headersEnded) {
                    responseHeaders.append(line).append("\r\n");
                    if (line.isEmpty()) {
                        headersEnded = true;
                    }
                } else {
                    responseBody.write(line.getBytes());
                }
            }

            String lastModified = extractHeader(responseHeaders.toString(), "Last-Modified");
            cache.put(url, new CachedPage(responseHeaders.toString(), responseBody.toByteArray(), lastModified));
            return true;

        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static String extractHeader(String headers, String headerName) {
        for (String line : headers.split("\r\n")) {
            if (line.startsWith(headerName + ":")) {
                return line.substring(headerName.length() + 2);
            }
        }
        return null;
    }

    private static String addCacheHeaders(String originalHeaders) {
        StringBuilder headers = new StringBuilder(originalHeaders);

        if (!originalHeaders.contains("Cache-Control")) {
            headers.append("Cache-Control: max-age=60\r\n");
        }
        headers.append("\r\n");
        return headers.toString();
    }

    private static class CachedPage {
        String headers;
        byte[] body;
        long timestamp;
        String lastModified;

        CachedPage(String headers, byte[] body, String lastModified) {
            this.headers = headers;
            this.body = body;
            this.lastModified = lastModified;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
