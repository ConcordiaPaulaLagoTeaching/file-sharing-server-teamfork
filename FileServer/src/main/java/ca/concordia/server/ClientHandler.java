package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final FileSystemManager fs;

    public ClientHandler(Socket socket, FileSystemManager fs) {
        this.socket = socket;
        this.fs = fs;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            out.println("OK Connected. Commands: CREATE <name>, WRITE <name> <hex>, READ <name>, DELETE <name>, LIST, QUIT");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) { out.println("ERROR empty command"); continue; }
                String[] p = line.trim().split("\\s+", 3);
                String cmd = p[0].toUpperCase();
                try {
                    switch (cmd) {
                        case "CREATE": {
                            if (p.length < 2) { out.println("ERROR usage: CREATE <filename>"); break; }
                            fs.createFile(p[1]);
                            out.println("OK");
                            break;
                        }
                        case "WRITE": {
                            if (p.length < 3) { out.println("ERROR usage: WRITE <filename> <hexpayload>"); break; }
                            fs.writeFile(p[1], hexToBytes(p[2]));
                            out.println("OK");
                            break;
                        }
                        case "READ": {
                            if (p.length < 2) { out.println("ERROR usage: READ <filename>"); break; }
                            byte[] data = fs.readFile(p[1]);
                            out.println("OK " + bytesToHex(data));
                            break;
                        }
                        case "DELETE": {
                            if (p.length < 2) { out.println("ERROR usage: DELETE <filename>"); break; }
                            fs.deleteFile(p[1]);
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
                            out.println("ERROR unknown command");
                    }
                } catch (Exception ex) {
                    out.println("ERROR " + ex.getMessage());
                }
            }
        } catch (IOException ignore) {
        } finally {
            try { socket.close(); } catch (IOException ignore) {}
            System.out.println("Client disconnected: " + socket);
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