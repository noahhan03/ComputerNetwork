import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class ProxyServer {

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
            System.out.println("url : " + url);
            System.out.println("[Proxy] Received request for: " + url);

            if (cache.containsKey(url)) {
                CachedPage cachedPage = cache.get(url);
                long currentTime = System.currentTimeMillis();
                long maxAgeMillis = MAXAGE * 1000;
                String cacheControl = extractHeader(cachedPage.headers, "Cache-Control");
                if (cacheControl != null && cacheControl.contains("max-age")) {
                    String[] directives = cacheControl.split(",");
                    for (String directive : directives) {
                        directive = directive.trim();
                        if (directive.startsWith("max-age=")) {
                            String maxAgeValue = directive.substring(8);
                            maxAgeMillis = Long.parseLong(maxAgeValue) * 1000;
                            break;
                        }
                    }
                }
                if (currentTime - cachedPage.timestamp < maxAgeMillis) {
                    System.out.println("[Proxy] Cache is valid for: " + url);

                    String ifModifiedSince = cachedPage.lastModified;
                    boolean isModified = forwardRequestToServer(clientSocket, url, ifModifiedSince);
                    isModified = false;
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
        } finally {
            try {
                clientSocket.close();
                System.out.println("[Proxy] Closed client socket.");
            } catch (IOException e) {
                System.err.println("[ERROR] Failed to close client socket: " + e.getMessage());
            }
        }
    }

    private static void sendCachedResponse(OutputStream clientOut, CachedPage cachedPage) throws IOException {
        System.out.println("[Proxy] Serving cached page");
        String headersWithCache = addCacheHeaders(cachedPage.headers);

        try {
            System.out.println("[DEBUG] Writing headers to clientOut...");
            clientOut.write(headersWithCache.getBytes(StandardCharsets.UTF_8));
            clientOut.write(cachedPage.body);
            clientOut.flush();
            System.out.println("[Proxy] Cached page served successfully.");
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to send cached response: " + e.getMessage());
            throw e;
        }
    }

    private static int findHeaderEnd(byte[] rawResponseBytes) {
        for (int i = 0; i < rawResponseBytes.length - 3; i++) {
            if (rawResponseBytes[i] == '\r' && rawResponseBytes[i + 1] == '\n' &&
                    rawResponseBytes[i + 2] == '\r' && rawResponseBytes[i + 3] == '\n') {
                return i + 4; // Position after the "\r\n\r\n"
            }
        }
        return -1; // End of headers not found
    }

    private static CachedPage fetchAndCachePage(String url) {
        try (Socket serverSocket = new Socket(WEB_SERVER_HOST, WEB_SERVER_PORT);
                InputStream serverInputStream = serverSocket.getInputStream();
                OutputStream serverOut = serverSocket.getOutputStream()) {

            // Send new request to WebServer
            String request = "GET " + url + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            serverOut.write(request.getBytes(StandardCharsets.ISO_8859_1));
            serverOut.flush();

            // Read response from server
            ByteArrayOutputStream rawResponse = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = serverInputStream.read(buffer)) != -1) {
                rawResponse.write(buffer, 0, bytesRead);
            }

            // Convert raw response to byte array
            byte[] rawResponseBytes = rawResponse.toByteArray();

            // Find the end of headers
            int headerEndIndex = findHeaderEnd(rawResponseBytes);

            if (headerEndIndex == -1) {
                System.err.println("[Proxy] Failed to parse response from WebServer.");
                return null;
            }

            // Extract headers and body
            String headersString = new String(rawResponseBytes, 0, headerEndIndex, StandardCharsets.ISO_8859_1);
            byte[] responseBody = Arrays.copyOfRange(rawResponseBytes, headerEndIndex, rawResponseBytes.length);

            // Extract Last-Modified header
            String lastModified = extractHeader(headersString, "Last-Modified");

            // Create CachedPage object
            CachedPage newPage = new CachedPage(headersString, responseBody, lastModified);

            // Store in cache
            cache.put(url, newPage);
            System.out.println("[Proxy] Cached page for: " + url);

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

            // WebServer에 요청 전송
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(url).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(WEB_SERVER_HOST).append("\r\n");
            if (ifModifiedSince != null) {
                request.append("If-Modified-Since: ").append(ifModifiedSince).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            serverOut.write(request.toString().getBytes());
            serverOut.flush();

            // WebServer 응답 처리
            String responseLine = serverIn.readLine();
            System.out.println("[Proxy] Received response from WebServer: " + responseLine);

            if (responseLine.startsWith("HTTP/1.1 304 Not Modified")) {
                return false; // 리소스 변경되지 않음
            }

            // 응답 헤더 및 본문 처리
            StringBuilder responseHeaders = new StringBuilder();
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            boolean headersEnded = false;
            String line;
            while ((line = serverIn.readLine()) != null) {
                if (!headersEnded) {
                    responseHeaders.append(line).append("\r\n");
                    if (line.isEmpty())
                        headersEnded = true;
                } else {
                    responseBody.write(line.getBytes());
                }
            }

            // 새로운 응답을 캐시에 저장
            String lastModified = extractHeader(responseHeaders.toString(), "Last-Modified");
            cache.put(url, new CachedPage(responseHeaders.toString(), responseBody.toByteArray(), lastModified));
            System.out.println("[Proxy] Cached updated page for: " + url);

            // 클라이언트로 응답 전달
            clientOut.write(responseHeaders.toString().getBytes());
            clientOut.write(responseBody.toByteArray());
            clientOut.flush();
            return true;

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String addCacheHeaders(String originalHeaders) {
        StringBuilder headers = new StringBuilder(originalHeaders);

        if (!originalHeaders.contains("Cache-Control")) {
            headers.append("Cache-Control: max-age=" + MAXAGE + "\r\n");
        }
        headers.append("\r\n");
        return headers.toString();
    }

    private static void sendErrorResponse(OutputStream clientOut, int statusCode, String message) throws IOException {
        String response = "HTTP/1.1 " + statusCode + " " + message + "\r\n\r\n";
        clientOut.write(response.getBytes());
    }

    private static String extractHeader(String headers, String headerName) {
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            int colonIndex = line.indexOf(":");
            if (colonIndex != -1) {
                String name = line.substring(0, colonIndex).trim();
                if (name.equalsIgnoreCase(headerName)) {
                    return line.substring(colonIndex + 1).trim();
                }
            }
        }
        return null;
    }

    private static String getCurrentTime() {
        SimpleDateFormat formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        return formatter.format(new Date());
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
