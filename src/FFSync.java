import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class FFSync {
    /*private class PacketResender extends TimerTask {
        private final DatagramSocket ds;
        private final Map<String,Long> files;
        private final Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets;

        PacketResender(DatagramSocket ds, Map<String,Long> files, Map<Short,AbstractMap.SimpleEntry<DatagramSocket,PackageInfo>> packets){
            this.ds      = ds;
            this.files   = files;
            this.packets = packets;
        }

        @Override
        public void run() {

        }
    }*/



    public static void main(String[] args) {
        /*try {
            teste2();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }*/

        String folderPath = args[0]; //Tem de acabar com a barra "/" no Linux ou com a barra "\" se for no Windows
        String externalIP = args[1];
        ReentrantLock readLock = new ReentrantLock();
        ReentrantLock writeLock = new ReentrantLock();
        Map<String,TransferWorker> requestsSent = new HashMap<>();
        Map<String,TransferWorker> requestsReceived = new HashMap<>();
        Map<String,Long> filesInDir;
        DatagramSocket ds;

        if (!testConnection(externalIP)) {
            System.out.println("Não existe conexão a internet");
            return;
        }

        try {
            filesInDir = fillDirMap(folderPath);
            ds = new DatagramSocket(9999);
            ds.connect(InetAddress.getByName(externalIP),9999);
            ds.setSoTimeout(10000); //10 seg de timeout
        }
        catch (SocketException e){
            System.out.println("Error opening connection socket");
            return;
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        ConnectionWorker receiver = new ConnectionWorker(true, externalIP, folderPath, filesInDir, ds, readLock, writeLock, requestsSent, requestsReceived);
        ConnectionWorker sender   = new ConnectionWorker(false, externalIP, folderPath, filesInDir, ds, readLock, writeLock, requestsSent, requestsReceived);

        ///Authentication Block
        FTrapid ft = new FTrapid(ds);
        Scanner sc = new Scanner(System.in);
        System.out.print("Introduza a sua password, em ambas as maquinas: ");
        String pass = sc.next();
        try {
           if (ft.authentication(pass)!=1) {
               System.out.println("Palavra passe errada");
               return;
           }
        } catch (Exception e) { //isto vai apanhar tanto a IOException como a exceção por limite
            e.printStackTrace();
            System.out.println("Erro na autenticação: " + e.getMessage());
            return;
        }
        System.out.println("Sucesso na autenticação!!");

        receiver.start();
        sender.start();

        try {
            receiver.join();
            sender.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void teste2() throws UnknownHostException {
        //args[0] is the name of the folder to be shared
        //args[1] is the IP adress of the computer ...
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(32000);
        } catch (SocketException e) {
            System.out.println("Erro a criar socket");
            return;
        }
        ds.connect(InetAddress.getByName("172.26.94.237"),31000);

        TransferWorker transferWorker = new TransferWorker(true,false,"/home/alexandrof/UNI/3ano1sem/CC/FilesGenerated/","test2.m4a",ds);
        transferWorker.run();
    }


    /* ******** Auxiliar Methods ******** */

    public static Map<String,Long> fillDirMap(String path) throws Exception {
        Map<String,Long> filesInDir = new HashMap<>();
        File dir = new File(path);

        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        filesInDir.put(name, data);
                    }
                }
            }
        }
        else throw new Exception("Diretoria não encontrada");

        return filesInDir;
    }


    public static boolean testConnection(String externalIP){
        try{
            InetAddress s = InetAddress.getByName(externalIP);
            // System.out.println("Ligado!!!");
            return true;
        }
        catch (IOException e) {/*System.out.println("Falhei!!!"); */return false;}
    }

    /* ******** Test Methods ******** */

    public static void generateFile(String filepath, int length){
        File file = new File(filepath);
        try {
            file.createNewFile();
        } catch (IOException e) {
            System.out.println("Couldnt create file");
            return;
        }
        FileOutputStream fops = null;

        try {
            fops = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            System.out.println("Error creating/opening file: " + filepath);
            return;
        }
        try {
            byte[] buffer = new byte[length];
            Random rand = new Random();
            rand.nextBytes(buffer);
            fops.write(buffer);
            fops.flush();
        } catch (IOException e) {
            System.out.println("Error writing file : " + filepath);
            return;
        }
    }
}
