package ca.concordia.server;

import java.io.*;
import java.net.*;
import ca.concordia.filesystem.FileSystemManager;

public class ClientHandler implements Runnable {
    private Socket socket;
    private FileSystemManager fs;

    public ClientHandler(Socket socket, FileSystemManager fs) {
        this.socket = socket;
        this.fs = fs;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                try {
                    String[] parts = line.trim().split(" ", 3);
                    String command = parts[0].toUpperCase();

                    switch (command) {
                        case "CREATE":
                            if (parts.length < 2)
                                throw new IllegalArgumentException("Missing filename");
                            fs.createFile(parts[1]);
                            out.println("OK: file created");
                            break;

                        case "WRITE":
                            if (parts.length < 3)
                                throw new IllegalArgumentException("Missing content");
                            fs.writeFile(parts[1], parts[2].getBytes());
                            out.println("OK: written");
                            break;

                        case "READ":
                            if (parts.length < 2)
                                throw new IllegalArgumentException("Missing filename");
                            byte[] data = fs.readFile(parts[1]);
                            out.println("OK: " + new String(data));
                            break;

                        case "DELETE":
                            if (parts.length < 2)
                                throw new IllegalArgumentException("Missing filename");
                            fs.deleteFile(parts[1]);
                            out.println("OK: file deleted");
                            break;

                        case "LIST":
                            String[] list = fs.listFiles();
                            out.println("OK: " + String.join(", ", list));
                            break;

                        case "QUIT":
                            out.println("OK: Goodbye");
                            return;

                        default:
                            out.println("ERROR: Unknown command");
                    }
                } catch (Exception e) {
                    // Send error back to the client, log it, and keep running
                    out.println("ERROR: " + e.getMessage());
                    System.err.println("[Client " + socket.getPort() + "] " + e);
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("Client disconnected: " + socket);
        }
    }
}
