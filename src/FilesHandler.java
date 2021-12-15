import java.io.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class FilesHandler {
    public static Map<String,Long> fillDirMap(String path){
        Map<String,Long> filesInDir = new HashMap<>();
        File dir = new File(path);
        System.out.println(File.separator);
        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        filesInDir.put(name, data);
                    }
                    else if (f.isDirectory()){
                        Map<String,Long> filesInDir2 = fillDirMap(f.getPath());
                        filesInDir2.forEach((k,v)-> filesInDir.put(f.getName()+"/"+k,v));
                    }
                }
            }
        }
        else return null;

        return filesInDir;
    }

    public static byte[] serialize(Map<String,Long> filesInDir) throws IOException {
        byte[] bytes;
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);

        System.out.println("Size: " + filesInDir.size());
        out.writeInt(filesInDir.size());

        for(Map.Entry<String,Long> entry : filesInDir.entrySet()){
            out.writeUTF(entry.getKey());
            out.writeLong(entry.getValue());
        }

        out.flush();
        bytes = byteOut.toByteArray();
        out.close();
        byteOut.close();

        return bytes;
    }

    public static Map<String,Long> deserialize(byte[] bytes) throws IOException {
        Map<String, Long> map = new HashMap<>();
        ByteBuffer bb = ByteBuffer.allocate(bytes.length);
        ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
        ObjectInputStream in = new ObjectInputStream(byteIn);

        int nFiles = in.readInt(); /*nFiles = bb.getInt();*/ System.out.println("nFiles: " + nFiles);

        for (;nFiles > 0;nFiles--)
            map.put(in.readUTF(),in.readLong());

        in.close();
        byteIn.close();
        return map;
    }

    public static String[] pathToArray(String path){
        return path.split("/");
    }

    public static String filePathgenerator (String folderPath, String filename, boolean receiver){
        String separator;
        if(!System.getProperty("file.separator").equals("/") ) separator="\\";
        else separator = "/";
        String[] ar = pathToArray(filename);
        StringBuilder sb = new StringBuilder();
        sb.append(folderPath);
        for (int i = 0; i < ar.length ;i++) {
            if (receiver && i !=0 && i == ar.length - 1) {
                File f2 = new File(sb.toString());
                f2.mkdirs();
            }
            sb.append(separator).append(ar[i]);
        }

        return sb.toString();
    }

    public static void deleteFile(String filepath){
        new File(filepath).delete();
    }
}
