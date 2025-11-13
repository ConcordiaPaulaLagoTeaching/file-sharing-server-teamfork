package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
 
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final FileSystemManager fs;
    private final ServerConfig cfg;

    public ClientHandler(Socket socket, FileSystemManager fs) {
        // For backward compatibility; will read limits from FileServer via cfg on construction there
        this(socket, fs, ServerConfig.sensibleDefaults(socket.getLocalPort()));
    }

    public ClientHandler(Socket socket, FileSystemManager fs, ServerConfig cfg) {
        this.socket = socket;
        this.fs = fs;
        this.cfg = cfg;
    }

    @Override
    public void run() {
        final String who = String.valueOf(socket.getRemoteSocketAddress());
        try (
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(new BoundedInputStream(socket.getInputStream(), cfg.maxLineLength), StandardCharsets.UTF_8),
                    8 * 1024
            );
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            // Greeting banner doubles as a connectivity check for clients
            out.println("OK: Connected. Commands: CREATE <name>, WRITE <name> <hex>, READ <name>, DELETE <name>, LIST, HELP, QUIT");

            String line;
            int commands = 0;

            while ((line = in.readLine()) != null) {
                commands++;
                if (commands > cfg.maxCommandsPerConnection) {
                    out.println("ERROR too many commands on one connection");
                    break; // defensive close
                }

                line = line.trim();
                if (line.isEmpty()) { out.println("ERROR empty command"); continue; }

                // Split into at most 3 tokens: CMD, arg1, arg2+
                String[] parts = line.split("\\s+", 3);
                String cmd = parts[0].toUpperCase(Locale.ROOT);

                try {
                    switch (cmd) {
                        case "HELP": {
                            out.println("OK Commands: CREATE <name>, WRITE <name> <hex>, READ <name>, DELETE <name>, LIST, QUIT");
                            break;
                        }
                        case "CREATE": {
                            if (parts.length < 2) { out.println("ERROR usage: CREATE <filename>"); break; }
                            String name = parts[1];
                            fs.createFile(name);
                            out.println("OK");
                            break;
                        }
                        case "WRITE": {
                            if (parts.length < 3) { out.println("ERROR usage: WRITE <filename> <hexpayload>"); break; }
                            String name = parts[1];
                            String hex = parts[2];
                            byte[] data = safeHexToBytes(hex, cfg.maxPayloadBytes);
                            fs.writeFile(name, data);
                            out.println("OK");
                            break;
                        }
                        case "READ": {
                            if (parts.length < 2) { out.println("ERROR usage: READ <filename>"); break; }
                            String name = parts[1];
                            byte[] data = fs.readFile(name);
                            out.println("OK " + bytesToHex(data));
                            break;
                        }
                        case "DELETE": {
                            if (parts.length < 2) { out.println("ERROR usage: DELETE <filename>"); break; }
                            String name = parts[1];
                            fs.deleteFile(name);
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
                            return; // close connection
                        }
                        default:
                            out.println("ERROR unknown command");
                    }
                } catch (IllegalArgumentException iae) {
                    // Any explicit validation error we throw as IllegalArgumentException
                    out.println("ERROR " + iae.getMessage());
                } catch (Exception e) {
                    // Filesystem or parsing errors – return error but keep the loop alive
                    String msg = e.getMessage();
                    out.println("ERROR " + (msg == null ? "internal error" : msg));
                }
            }
        } catch (IOException e) {
            // Connection closed / timed out / reset – nothing to do.
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

    // -------- Helpers (robust hex + limits) --------

    private static byte[] safeHexToBytes(String s, int maxBytes) throws IllegalArgumentException {
        String t = s.replaceAll("\\s+", "");
        if ((t.length() & 1) != 0) throw new IllegalArgumentException("hex payload must have even length");
        int outLen = t.length() / 2;
        if (outLen > maxBytes) throw new IllegalArgumentException("payload too large (max " + maxBytes + " bytes)");
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) {
            int hi = hexNibble(t.charAt(2 * i));
            int lo = hexNibble(t.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex character");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return -1;
    }

    private static String bytesToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * BoundedInputStream: caps how many characters can be read in a single line,
     * preventing an attacker from sending an infinite line and exhausting memory.
     */
    static final class BoundedInputStream extends FilterInputStream {
        private final long maxChars;
        private long seen = 0;

        BoundedInputStream(InputStream in, long maxChars) {
            super(in);
            this.maxChars = maxChars;
        }

        @Override public int read() throws IOException {
            int r = super.read();
            if (r >= 0) { seen++; if (seen > maxChars) throw new IOException("line too long"); }
            return r;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) {
                seen += r;
                if (seen > maxChars) throw new IOException("line too long");
            }
            return r;
        }
    }
}
