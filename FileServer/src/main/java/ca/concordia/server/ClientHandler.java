package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Handles exactly one client connection.
 * Safe with many concurrent instances thanks to FileSystemManager's read/write locks.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final FileSystemManager fs;

    public ClientHandler(Socket socket, FileSystemManager fs) {
        this.socket = socket;
        this.fs = fs;
    }

    @Override
    public void run() {
        String who = socket.getRemoteSocketAddress().toString();
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            out.println("OK: Connected. Commands: CREATE <name>, WRITE <name> <hex>, READ <name>, DELETE <name>, LIST, QUIT");

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) { out.println("ERROR: empty command"); continue; }

                // Split at most into 3 parts: CMD, arg1, arg2+
                String[] parts = line.trim().split("\\s+", 3);
                String cmd = parts[0].toUpperCase();

                try {
                    switch (cmd) {
                        case "CREATE": {
                            if (parts.length < 2) { out.println("ERROR: usage CREATE <filename>"); break; }
                            fs.createFile(parts[1]);
                            out.println("OK");
                            break;
                        }
                        case "WRITE": {
                            if (parts.length < 3) { out.println("ERROR: usage WRITE <filename> <hexpayload>"); break; }
                            byte[] data = hexToBytes(parts[2]);
                            fs.writeFile(parts[1], data);
                            out.println("OK");
                            break;
                        }
                        case "READ": {
                            if (parts.length < 2) { out.println("ERROR: usage READ <filename>"); break; }
                            byte[] data = fs.readFile(parts[1]);
                            out.println("OK " + bytesToHex(data));
                            break;
                        }
                        case "DELETE": {
                            if (parts.length < 2) { out.println("ERROR: usage DELETE <filename>"); break; }
                            fs.deleteFile(parts[1]);
                            out.println("OK");
                            break;
                        }
                        case "LIST": {
                            String[] names = fs.listFiles();
                            out.println("OK " + String.join(",", names));
                            break;
                        }
                        case "QUIT": {
                            out.println("OK bye");
                            return;
                        }
                        default:
                            out.println("ERROR: unknown command");
                    }
                } catch (Exception e) {
                    out.println("ERROR: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // client disconnected or timed out; just log
            System.err.println("[Client " + who + "] " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // Utility used by FileServer when queue is full
    static void respondAndClose(Socket s, String msg) {
        try (OutputStream os = s.getOutputStream();
             PrintWriter out = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {
            out.println(msg);
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] hexToBytes(String s) throws IOException {
        String t = s.replaceAll("\\s+", "");
        if ((t.length() & 1) != 0) throw new IOException("hex must have even length");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(2*i, 2*i+2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
