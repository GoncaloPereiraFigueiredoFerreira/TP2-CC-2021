import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.util.*;
import static java.lang.Thread.sleep;

//TODO: Receive com número superior ao n de threads n está a funcionar

public class FFSync {
    private static int MAXTHREADSNUMBERPERFUNCTION = 30; //if MAXTHREADSNUMBERPERFUNCTION = 10, then 10 threads can send files, and another 10 threads can receive files

    private static final int REQUESTSPORT = 9999;
    private static PrintWriter pw;

    public static void main(String[] args) {
        String folderPath = args[0]; //Tem de acabar com a barra "/" no Linux ou com a barra "\" se for no Windows
        InetAddress externalIP;
        SharedInfo si;
        Map<String,Long> filesInDir;
        DatagramSocket ds;


        //Sets the maximum number of threads per function(sending/receiving) and/or the window size
        Integer windowSize = null;
        try {

            if(args.length > 2 && args.length <= 6 && args.length % 2 == 0 ){

                for(int i = 1 ; i < (args.length / 2) ; i++){

                    if("-t".equals(args[2*i])) {
                        int nrThreads = Integer.parseInt(args[2*i + 1]);
                        if(nrThreads > 0) MAXTHREADSNUMBERPERFUNCTION = nrThreads;
                    }
                    else if("-w".equals(args[2*i])){
                        windowSize = Integer.parseInt(args[2*i + 1]);
                        if(windowSize <= 0) windowSize = null;
                    }

                }

            }

        } catch (NumberFormatException ignored) {}

        //Creates logging file
        try { pw = new PrintWriter("log.txt"); }
        catch (FileNotFoundException e) {
            System.out.println("Couldn't create logging file.");
            return;
        }


        //Connection verifications
        if((externalIP = testConnection(args[1])) != null)
            writeToLogFile("CONNECTION TEST: Client found!");
        else {
            writeToLogFile("CONNECTION TEST: Couldn't reach the other client!");
            return;
        }


        //Initiates connection with the other client
        try {
            ds = new DatagramSocket(REQUESTSPORT);
            writeToLogFile("REQUEST SOCKET: Socket created successfully!");
        }
        catch (SocketException e){
            writeToLogFile("REQUEST SOCKET: Error creating socket!");
            return;
        }


        //Authentication Block
        FTrapid ftr = new FTrapid(ds, externalIP, (short)REQUESTSPORT, windowSize);
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
        String localIP = FFSync.getLocalIP(externalIP);
        filesInDir = getFilesToBeSent(ftr, localIP, args[1], folderPath);
        if(filesInDir == null) return;
        si = new SharedInfo(folderPath, externalIP, REQUESTSPORT, filesInDir.keySet(), pw, windowSize);

        //Starts the worker responsible for answering the http requests
        HTTPRequestsAnswerer httpSv = new HTTPRequestsAnswerer(localIP, si);
        httpSv.start();

        //Starts a connection worker. This worker is responsible for answering requests
        ConnectionWorker cw = new ConnectionWorker(ds, ftr, si);
        cw.start();

        //Starts threads for each file that needs to be sent, taking into account the number of threads established
        sendWriteRequests(si);

        //Waits for connection worker to finish
        try { cw.join(); httpSv.stopServer(); httpSv.join(); }
        catch (InterruptedException ignored) {}

        ds.close();
    }

    /* ******** Main Methods ******** */

    public static InetAddress testConnection(String externalIP) {
        try { return InetAddress.getByName(externalIP); }
        catch (UnknownHostException e) { return null; }
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
            if (filesInDir.containsKey(filename) && filesInDir.get(filename) <= entry.getValue())
                filesInDir.remove(filename);
        }

        return filesInDir;
    }

    private static void sendWriteRequests(SharedInfo si) {
        DatagramSocket datagramSocket;
        String filename;

        while((filename = si.status.pollNextFile()) != null) {

            //Gets local usable port for a new transfer worker
            datagramSocket = createDatagramSocket();
            while (datagramSocket == null) {
                //TODO: Unlikely to happen, but check how the time of sleep influences the rest
                //Sleeps 1 second and tries to get a valid socket again
                try {
                    sleep(25);
                } catch (InterruptedException ignored) {}
                datagramSocket = createDatagramSocket();
            }

            //Waits for threads(senders) to be available
            try {
                si.sendRequestsLock.lock();

                while (si.getSendersCount() >= FFSync.MAXTHREADSNUMBERPERFUNCTION)
                    si.sendRequestsCond.await();

                //Increments the number of threads
                si.incSendersCount();
            }
            catch (InterruptedException ignored) {}
            finally { si.sendRequestsLock.unlock(); }

            //Creates a Transfer Worker. This worker is responsible for sending the file to the other client, after performing a request to the other client, and receiving confirmation(SYN).
            TransferWorker tw = new TransferWorker(true, false, filename, datagramSocket, (short) si.requestsPort, si, null);
            tw.start();
            si.status.addRequestSent(filename, tw);
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

    private static String getLocalIP(InetAddress externalIP){
        DatagramSocket datagramSocket = createDatagramSocket();
        datagramSocket.connect(externalIP, REQUESTSPORT);
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

    private static void writeToLogFile(String msg){
        if(pw == null) return;
        LocalDateTime time = LocalDateTime.now();
        pw.write( time.getHour() + ":" + time.getMinute() + ":" + time.getSecond() + " => " + msg + "\n"); pw.flush();
    }
}
