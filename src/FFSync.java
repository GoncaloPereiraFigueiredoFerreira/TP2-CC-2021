import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Thread.sleep;

public class FFSync {
    private static int MAXTHREADSNUMBERPERFUNCTION = 30; //if MAXTHREADSNUMBERPERFUNCTION = 10, then 10 threads can send files, and another 10 threads can receive files
    private static int REQUESTSPORT = 11111;
    private static String externalIP;
    private static PrintWriter pw;

    //TODO: Needs to wait for all threads (receivers and senders). It doesnt, but we need a thread attending to http requests

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

        //Sets the maximum number of threads per function(sending/receiving)
        try {
            if(args.length == 3) {
                int nrThreads = Integer.parseInt(args[2]);
                if(nrThreads > 0) MAXTHREADSNUMBERPERFUNCTION = nrThreads;
            }
        } catch (NumberFormatException ignored) {}


        //Creates logging file
        try { pw = new PrintWriter("log.txt"); }
        catch (FileNotFoundException e) {
            System.out.println("Couldn't create logging file.");
            return;
        }


        //Connection verifications
        if(testConnection(externalIP))
            writeToLogFile("CONNECTION TEST: Client found!");
        else {
            writeToLogFile("CONNECTION TEST: Couldn't reach the other client!");
            return;
        }


        //Initiates connection with the other client
        try {
            ds = new DatagramSocket(REQUESTSPORT);
            writeToLogFile("REQUEST SOCKET: Socket created successfully!");
            ds.setSoTimeout(10000); //10 seconds timeout
        }
        catch (SocketException e){
            writeToLogFile("REQUEST SOCKET: Error creating socket!");
            return;
        }


        //Authentication Block
        FTrapid ftr = new FTrapid(ds, externalIP, (short)REQUESTSPORT);
        Scanner sc  = new Scanner(System.in);
        System.out.print("Introduza a sua password, em ambas as maquinas: ");
        String pass = sc.next(); sc.close();
        try {
            if (ftr.authentication(pass) != 1) {
                System.out.println("Wrong Password!");
                writeToLogFile("AUTHENTICATION: Failed. Wrong password was inserted.");
                return;
            }
            else writeToLogFile("AUTHENTICATION: Success!");
        } catch (Exception e) { //isto vai apanhar tanto a IOException como a exceção por limite
            System.out.println("Erro na autenticação: " + e.getMessage());
            return;
        }
        System.out.println("Authentication succeded!!");


        //Fills the map with the files present in the given directory
        String localIP = FFSync.getLocalIP(externalIP); System.out.println(localIP);
        filesInDir = getFilesToBeSent(ftr, localIP, externalIP, folderPath);
        if(filesInDir == null) return;


        //Starts a connection worker. This worker is responsible for answering requests
        ConnectionWorker cw = new ConnectionWorker(receivers, externalIP, folderPath, ds, ftr, receiveLock, sendLock, requestsReceived);
        cw.start();


        //Starts threads for each file that needs to be sent, taking into account the number of threads established
        sendWriteRequests(externalIP, sendLock, folderPath, filesInDir, requestsSent, senders);


        //Waits for connection worker to finish
        try { cw.join(); }
        catch (InterruptedException ignored) {}


        ds.close();
    }

    /* ******** Main Methods ******** */

    public static boolean testConnection(String externalIP){
        try{ return InetAddress.getByName(externalIP).isReachable(30000); }
        catch (IOException e) {return false;}
    }

    private static Map<String,Long> getFilesToBeSent(FTrapid ftr, String localIP, String externalIP, String folderPath) {
        Map<String, Long> filesInDir = FilesHandler.fillDirMap(folderPath),
                          filesInDirReceived;

        //Fills the map with the files present in the given directory
        if (filesInDir == null) {
            writeToLogFile("FILES VERIFICATION: The path given does not match with a directory!");
            return null;
        }

        //Decides the order in which it will receive the map of files from the other client
        try {
            if (localIP.compareTo(externalIP) < 0) {
                ftr.sendData(FilesHandler.serialize(filesInDir));
                filesInDirReceived = FilesHandler.deserialize(ftr.receiveData());
            } else {
                filesInDirReceived = FilesHandler.deserialize(ftr.receiveData());
                ftr.sendData(FilesHandler.serialize(filesInDir));
            }
        } catch (Exception e) {
            writeToLogFile("FILES VERIFICATION: Error sending/receiving list of files!");
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
            TransferWorker tw = new TransferWorker(senders, true, false, folderPath, filename, datagramSocket, externalIP, (short) REQUESTSPORT, sendLock);
            tw.start();
            requestsSent.put(filename, tw);

            //Removes the file from the "queue"
            it.remove();
        }
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

    private static String getLocalIP(String externalIP){
        DatagramSocket datagramSocket = createDatagramSocket();
        try { datagramSocket.connect(InetAddress.getByName(externalIP), REQUESTSPORT); }
        catch (UnknownHostException ignored) {}
        String localIP = datagramSocket.getLocalAddress().getHostAddress();
        datagramSocket.close();
        return localIP;
    }

    public static int getMAXTHREADSNUMBERPERFUNCTION(){
        return MAXTHREADSNUMBERPERFUNCTION;
    }

    public static int getREQUESTSPORT(){
        return REQUESTSPORT;
    }

    public static void writeToLogFile(String msg){
        if(pw == null) return;
        pw.write(msg + "\n");
    }
}
