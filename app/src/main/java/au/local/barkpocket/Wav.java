package au.local.barkpocket;
import java.io.*;
final class Wav {
    static void write(File file, byte[] pcm) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); le(out, 36 + pcm.length, 4);
            out.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); le(out,16,4); le(out,1,2); le(out,1,2);
            le(out,16000,4); le(out,32000,4); le(out,2,2); le(out,16,2);
            out.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); le(out,pcm.length,4); out.write(pcm); out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new IOException("Cannot commit recorded phrase.");
    }
    private static void le(OutputStream out, int n, int count) throws IOException { for(int i=0;i<count;i++) out.write((n >>> (i*8)) & 255); }
}
