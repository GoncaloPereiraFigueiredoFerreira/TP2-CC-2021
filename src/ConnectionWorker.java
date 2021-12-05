
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionWorker extends Thread {
    /*
     * 1. metodo read e write que le ficheiro e transforma em array de bytes, tendo em conta tamanho maximo de 2^(16) * 1019
     *
     * 2. abrir e lidar com sockets
     * 3. threads
     * 4. http por tcp
     * 5. status
     *
     */

    /* Cliente que manda
     * 1. Envia Request pela porta 80 (udp), inclui porta livre
     * 2. Espera por SYN ( vem acompanhado da porta livre do servidor)
     * 3. Cria DatagramSocket e iniciamos conexao
     * 4. Criada thread com o socket
     * 5. Preparar ficheiros de ler/escrever
     * 6. criar ftRapid com o socket
     */

    /* Cliente que recebe
     * 1. Espera por request na porta 80
     * 2. Devolve SYN com porta livre
     * 3. Cria Socket e inicia conexao
     * 4. Cria thread com socket
     * 5. Preparar ficheiros ler/escrever
     * 6. Criar ftRapid com o socket
     */

    private final String externalIP; //IP of the other client
    private final String folderPath;
    private final Map<String,Long> filesInDir;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final ReentrantLock readLock;
    private final ReentrantLock writeLock;
    private final Map<String,TransferWorker> requestsSent;
    private final Map<String,TransferWorker> requestsReceived;
    private final boolean receiver;

    ConnectionWorker(boolean receiver, String externalIP, String folderPath, Map<String,Long> filesInDir, DatagramSocket ds, ReentrantLock readLock, ReentrantLock writeLock, Map<String,TransferWorker> requestsSent, Map<String,TransferWorker> requestsReceived){
        this.receiver         = receiver;
        this.externalIP       = externalIP;
        this.folderPath       = folderPath;
        this.filesInDir       = filesInDir;
        this.ds               = ds;
        this.ftr              = new FTrapid(ds);
        this.readLock         = readLock;
        this.writeLock        = writeLock;
        this.requestsSent     = requestsSent;
        this.requestsReceived = requestsReceived;
    }

    @Override
    public void run(){
        if(receiver) {
            try {
                receive();
            } catch (IOException | OpcodeNotRecognizedException e) {
                e.printStackTrace();
            }
        }
        else {
            try {
                sendWriteRequest();
            } catch (IOException | OpcodeNotRecognizedException e) {
                e.printStackTrace();
            }
        }
    }

    //TODO: Delete file in the receiver client if there is an error while receiving

    //returns false if we already own that file in a more recent version
    public boolean analyse(RequestPackageInfo rq){
        String filename = rq.getFilename();
        boolean ret = true;
        if (this.filesInDir.containsKey(filename)){
            ret = this.filesInDir.get(filename) < rq.getData();
            if (ret) this.filesInDir.remove(filename);
        }
        return ret;
    }

    /*
    //TODO: FLuxo de 2 portas, 1 para requests outra para Syns/Errors
    public void sendRequests() throws IOException, OpcodeNotRecognizedException {
        Set<String> rq = this.filesInDir.keySet();
        DatagramSocket datagramSocket = null;
        short port = 0;

        for (String s : rq){
            boolean flag = true;
            //Gets local usable port
            if(datagramSocket == null) {
                datagramSocket = createDatagramSocket();
                port = (short) datagramSocket.getPort();
            }

            while (flag) {
                try {
                    writeLock.lock();
                    readLock.lock();
                    ftr.requestRRWR(s, port, (short) 2, this.filesInDir.get(s));
                } finally { writeLock.unlock(); }

                DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXSYNSIZE], FTrapid.MAXSYNSIZE);

                try{

                    ds.receive(dp);
                }finally {readLock.unlock();}

                short msg = ftr.analyseAnswer(dp);
                if (ftr.verifyPackage(dp.getData()) == 1) System.out.println("Erro"); //(TODO) ocorreu um erro
                else if (ftr.verifyPackage(dp.getData()) == 2) {
                    //retribuiu um port
                    //start worker thread (portLocal, portDoOutro)
                    datagramSocket = null;
                    flag=false;
                }

            }
        }

        //Closed if not needed
        if(datagramSocket != null) datagramSocket.close();
    }*/

    public void sendWriteRequest() throws IOException, OpcodeNotRecognizedException {
        Set<String> rq = this.filesInDir.keySet();
        DatagramSocket datagramSocket = null;
        short port;

        for (String filepath : rq){
            //Gets local usable port
            datagramSocket = createDatagramSocket();
            port = (short) datagramSocket.getLocalPort(); System.out.println("Vou escrever por esta porta: " + port);//(PRINT)

            if(datagramSocket != null){
                try {
                    writeLock.lock();
                    ftr.requestRRWR(filepath, port, (short) 2, this.filesInDir.get(filepath));
                } finally { writeLock.unlock(); }
                TransferWorker tw = new TransferWorker(true, false, folderPath, filepath, datagramSocket);
                requestsSent.put(filepath, tw);
            }
        }
    }

    //TODO: add locks to the data structures
    public void receive() throws IOException, OpcodeNotRecognizedException {
        DatagramSocket dsTransferWorker = null;
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean flag = true;

        short port = 0;

        while (flag) {
            try {
                readLock.lock();
                ds.receive(dp);
            } finally {
                readLock.unlock();
            }

            //Received Write Request
            if (ftr.verifyPackage(dp.getData()) == FTrapid.WRopcode) {
                RequestPackageInfo rpi = ftr.analyseRequest(dp);
                String filename = rpi.getFilename();

                //New Request
                if (!requestsReceived.containsKey(filename)) {
                    //Checks for the existence of the file.
                    //If the file exists, compares the dates when they were last modified.
                    //Creates a transfer worker(thread)
                    if (!filesInDir.containsKey(filename) || filesInDir.get(filename) < rpi.getData()) {
                        dsTransferWorker = createDatagramSocket();
                        //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests
                        TransferWorker tw = new TransferWorker(false, true, folderPath, filename, dsTransferWorker);
                        if(!tw.isAlive()) {
                            tw.connectToPort(externalIP,rpi.getPort()); System.out.println("Recebi esta porta: " + rpi.getPort());//(PRINT)
                            tw.start();
                        }
                        requestsReceived.put(filename, tw);
                        ftr.answer((short) 2, (short) dsTransferWorker.getLocalPort(), filename);
                    }
                    //Rejects the write request if the date of the local file is the latest
                    else{
                        ftr.answer((short) 1, (short) 401, filename);
                    }
                } else {
                    //Duplicate of a previous request
                    //Send SYN package (Resended in case of a DUP Request)
                    ftr.answer((short) 2, requestsReceived.get(filename).getLocalPort(), filename);
                }
            }
            //Received SYN
            else if (ftr.verifyPackage(dp.getData()) == FTrapid.SYNopcode) {
                ErrorSynPackageInfo espi = ftr.analyseAnswer(dp);
                String filename = espi.getFilename();
                TransferWorker tw;
                //If there is a transfer worker associated with the file name received, starts it, if it isnt already running
                if((tw = requestsSent.get(filename)) != null){
                    if(!tw.isAlive()) {
                        tw.connectToPort(externalIP,espi.getMsg());
                        tw.start();
                    }
                }
                //Sends an error if there isnt a "log" of a thread in charge of sending a file with the name received
                else{
                    ftr.answer((short) 1, (short) 403, filename);
                }
            }
            else if(ftr.verifyPackage(dp.getData()) == FTrapid.ERRopcode){
                ErrorSynPackageInfo espi = ftr.analyseAnswer(dp);
                short errorCode = espi.getMsg();
                String filename = espi.getFilename();

                //TODO: Add all the errors
                //Connection error
                if(errorCode == 400){

                }
                //Already owned file or the local file is the latest
                else if(errorCode == 401){
                    TransferWorker tw = requestsSent.remove(filename);
                    tw.closeSocket();
                }
                else if(errorCode == 402){
                    //Remover o ficheiro na maquina que recebe
                }
                else if(errorCode == 403){
                    //TODO: O que fazer aqui?
                }
            }
        }
    }

    /*
    public void receiveRequest() throws IOException, OpcodeNotRecognizedException {
        DatagramPacket dp = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE],FTrapid.MAXRDWRSIZE);
        boolean flag = true;
        DatagramSocket datagramSocket = null;
        short port = 0;

        while (flag) {
            try {
                ds.receive(dp);
                RequestPackageInfo rq = ftr.analyseRequest(dp);

                //Gets local usable port
                if(datagramSocket == null) {
                    datagramSocket = createDatagramSocket();
                    port = (short) datagramSocket.getPort();
                }

                if (analyse(rq)) {
                    ftr.answer((short) 1, port); //SYN
                    //start worker thread
                    datagramSocket = null;
                } else ftr.answer((short) 2, (short) 404); //erro n quero receber esse pacote
            }
            catch (IOException e){flag = false;}
        }
    }*/





/* ********** Auxiliar Methods ********** */

    /*
    Returns DatagramSocket with the first non-privileged available port.
    Returns null if there isnt an available port
     */
    private DatagramSocket createDatagramSocket(){
        boolean validPort = false;
        DatagramSocket ds = null;
        for(int port = 1024; !validPort && port <= 32767 ; port++){
            try {
                ds = new DatagramSocket(port);
                validPort = true;
            } catch (SocketException ignored) {}
        }
        return ds;
    }

    public void fillDirMap(String path) throws Exception {
        File dir = new File(path);
        if (dir.isDirectory()){
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        long data = f.lastModified();
                        String name = f.getName();
                        this.filesInDir.put(name, data);
                    }
                }
            }
        }
        else throw new Exception("Diretoria não encontrada");
    }

    public boolean testConnection(){
        try{
            InetAddress s = InetAddress.getByName("www.google.com");
           // System.out.println("Ligado!!!");
            return true;
        }
        catch (IOException e) {/*System.out.println("Falhei!!!"); */return false;}
    }



    /* ********* Main ************ */
/*
    public static void main(String[] args) throws UnknownHostException {
        args = new String[2];
        Scanner sc = new Scanner(System.in);
        args[0] = "/home/alexandrof/UNI/3ano1sem/CC/TP2-CC-2021/test.m4a";
        //System.out.print("Pasta:");
        //args[0]=sc.next();
        System.out.print("IP:");
        args[1]=sc.next();
        teste2();
    }*/

    /* ******** Test Methods ************ */

   /* public static void teste2() throws UnknownHostException {
        //args[0] is the name of the folder to be shared
        //args[1] is the IP adress of the computer ...
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(13333);
        } catch (SocketException e) {
            System.out.println("Erro a criar socket");
            return;
        }
        ds.connect(InetAddress.getByName("localhost"),12222);

        TransferWorker transferWorker = new TransferWorker(false,false,"/home/alexandrof/UNI/3ano1sem/CC/TP2-CC-2021/test.m4a",ds);
        transferWorker.run();
    }*/
}
