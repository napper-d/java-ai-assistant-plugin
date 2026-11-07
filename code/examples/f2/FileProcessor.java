import java.io.*;

/**
 * Demo B — Bug Fix (Lec-3.1 Exceptions)
 *
 * Bug: catch (Exception e) swallows all exceptions including IOException;
 *      resources are never closed on failure; caller receives null with no signal.
 *
 * Instruction:
 * "Fix the readConfig method so that it properly handles IOException
 *  with try-with-resources and does not swallow exceptions.
 *  Preserve the class name, method signature, and all unrelated code."
 *
 * To run before fix (shows silent failure):
 *   java examples/f2/FileProcessor.java
 */
public class FileProcessor {

    public String readConfig(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        FileProcessor fp = new FileProcessor();
        // File does not exist — bug silently returns null
        String config = fp.readConfig("nonexistent.txt");
        System.out.println("Config: " + config);
    }
}
