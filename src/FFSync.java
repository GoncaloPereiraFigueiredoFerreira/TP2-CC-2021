import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Thread.sleep;

public class FFSync {
    public static final int MAXTHREADSNUMBER = 60; //Cannot be inferior than 10
    public static final int MAXTHREADSNUMBERPERFUNCTION = 30; //if MAXTHREADSNUMBERPERFUNCTION = 10, then 10 threads can send files, and another 10 threads can receive files
    private static final int REQUESTSPORT = 9999;

    public static void main(String[] args) {
        String folderPath = args[0]; //Tem de acabar com a barra "/" no Linux ou com a barra "\" se for no Windows
        String externalIP = args[1];
        ReentrantLock receiveLock = new ReentrantLock();
        ReentrantLock sendLock    = new ReentrantLock();
        ThreadGroup senders       = new ThreadGroup("FFSyncSenders");
        ThreadGroup receivers     = new ThreadGroup("FFSyncReceivers");
        Map<String,TransferWorker> requestsReceived = new HashMap<>();
        Map<String,TransferWorker> requestsSent     = new HashMap<>();
        Map<String,Long> filesInDir;
        DatagramSocket ds;

        //Connection verifications
        if (!testConnection(externalIP)) {
            System.out.println("Couldn't find the other client!");
            return;
        }

        //Initiates connection with the other client
        try {
            ds = new DatagramSocket(REQUESTSPORT);
            ds.connect(InetAddress.getByName(externalIP),REQUESTSPORT);
            ds.setSoTimeout(10000); //10 seconds timeout
        }
        catch (SocketException e){
            System.out.println("Error opening connection socket!");
            return;
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        //Authentication Block
        FTrapid ftr = new FTrapid(ds);
        Scanner sc  = new Scanner(System.in);
        System.out.print("Introduza a sua password, em ambas as maquinas: ");
        String pass = sc.next(); sc.close();
        try {
           if (ftr.authentication(pass)!=1) {
               System.out.println("Palavra passe errada");
               return;
           }
        } catch (Exception e) { //isto vai apanhar tanto a IOException como a exceção por limite
            e.printStackTrace();
            System.out.println("Erro na autenticação: " + e.getMessage());
            return;
        }
        System.out.println("Sucesso na autenticação!!");

        //Fills the map with the files present in the given directory
        filesInDir = getFilesToBeSent(ftr, ds.getLocalAddress().getHostAddress(), externalIP, folderPath);
        if(filesInDir == null) return;

        //Starts a connection worker. This worker is responsible for answering requests
        ConnectionWorker cw = new ConnectionWorker(receivers, externalIP, folderPath, ds, ftr, receiveLock, sendLock, requestsSent, requestsReceived);
        cw.start();

        //Starts threads for each file that needs to be sent, taking into account the number of threads established
        sendWriteRequests(externalIP, sendLock, folderPath, filesInDir, requestsSent, senders);

        //Waits for connection worker to finish
        try { cw.join(); }
        catch (InterruptedException ignored) {}
    }

    /* ******** Main Methods ******** */

    public static boolean testConnection(String externalIP){
        try{
            InetAddress s = InetAddress.getByName(externalIP);
            return true;
        }
        catch (IOException e) {return false;}
    }

    private static Map<String,Long> getFilesToBeSent(FTrapid ftr, String localIP, String externalIP, String folderPath) {
        Map<String, Long> filesInDir = fillDirMap(folderPath),
                          filesInDirReceived;

        //Fills the map with the files present in the given directory
        if (filesInDir == null) {
            System.out.println("Not a directory!");
            return null;
        }

        //Decides the order in which it will receive the map of files from the other client
        try {
            if (localIP.compareTo(externalIP) < 0) {
                ftr.sendData(serialize(filesInDir));
                filesInDirReceived = deserialize(ftr.receiveData());
            } else {
                filesInDirReceived = deserialize(ftr.receiveData());
                ftr.sendData(serialize(filesInDir));
            }
        } catch (Exception e) {
            System.out.println("Error sending/receiving list of files!");
            return null;
        }

        //Corrects the map of files that need to be sent
        //Removes all the files from the first map, that match the name of a file from the other machine, but are not as recent
        String filename;
        for (Map.Entry<String, Long> entry : filesInDirReceived.entrySet()) {
            filename = entry.getKey();
            //Checks for the existence of the file. If the file exists, compares the dates when they were last modified.
            if (filesInDir.containsKey(filename) && filesInDir.get(filename) < entry.getValue())
                filesInDir.remove(filename);
        }

        return filesInDir;
    }

    private static void sendWriteRequests(String externalIP, ReentrantLock sendLock, String folderPath, Map<String,Long> filesInDir, Map<String,TransferWorker> requestsSent, ThreadGroup senders) {
        DatagramSocket datagramSocket;

        Iterator<Map.Entry<String,Long>> it = filesInDir.entrySet().iterator();
        Map.Entry<String,Long> entry; String filename;

        while(it.hasNext()) {
            entry = it.next();
            filename = entry.getKey();

            //Gets local usable port for a new transfer worker
            datagramSocket = createDatagramSocket();
            while (datagramSocket == null) {
                //Sleeps 1 second and tries to get a valid socket again
                try {
                    sleep(500);
                } catch (InterruptedException ignored) {
                }
                datagramSocket = createDatagramSocket();
            }

            //Waits for threads(senders) to be available
            while (senders.activeCount() >= FFSync.MAXTHREADSNUMBERPERFUNCTION) {
                try   {sleep(500);}
                catch (InterruptedException ignored) {} //Maybe use condition.signal
            }

            //Creates a Transfer Worker. This worker is responsible for sending the file to the other client, after performing a request to the other client, and receiving confirmation(SYN).
            TransferWorker tw = new TransferWorker(senders, true, false, folderPath, filename, datagramSocket, sendLock);
            tw.connectToPort(externalIP, REQUESTSPORT);
            tw.start();
            requestsSent.put(filename, tw);

            //Removes the file from the "queue"
            it.remove();
        }

        System.out.println("Finished sending requests!");
    }

    /* ******** Auxiliar Methods ******** */

    /*
    *Returns DatagramSocket with the first non-privileged available port.
    *Returns null if there isnt an available port
     */
    public static DatagramSocket createDatagramSocket() {
        boolean validPort = false;
        DatagramSocket ds = null;
        for (int port = 1024; !validPort && port <= 32767; port++) {
            try {
                ds = new DatagramSocket(port);
                validPort = true;
            } catch (SocketException ignored) {}
        }

        return ds;
    }

    public static String[] pathToArray(String path){
        return path.split("/");
    }

    private static Map<String,Long> fillDirMap(String path){
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

    private static byte[] serialize(Map<String,Long> filesInDir) throws IOException {
        byte[] bytes;
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);

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

    private static Map<String,Long> deserialize(byte[] bytes) throws IOException {
        Map<String, Long> map = new HashMap<>();
        ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
        ObjectInputStream in = new ObjectInputStream(byteIn);

        int nFiles = in.readInt();

        for (;nFiles > 0;nFiles--)
            map.put(in.readUTF(),in.readLong());

        byteIn.close();
        in.close();
        return map;
    }
}
