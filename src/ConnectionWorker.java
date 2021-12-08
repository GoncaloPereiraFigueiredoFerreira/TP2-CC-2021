import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionWorker extends Thread {
    private final String externalIP; //IP of the other client
    private final String folderPath;
    private final Map<String,Long> filesInDir;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final ReentrantLock readLock;
    private final ReentrantLock writeLock;
    private final Map<String,TransferWorker> requestsSent;
    private final Map<String,TransferWorker> requestsReceived; //Keeps track of the files received and the files that are being received
    private final boolean receiver;

    ConnectionWorker(boolean receiver, String externalIP, String folderPath, Map<String, Long> filesInDir, DatagramSocket ds, FTrapid ftr, ReentrantLock readLock, ReentrantLock writeLock, Map<String, TransferWorker> requestsSent, Map<String, TransferWorker> requestsReceived) {
        this.receiver         = receiver;
        this.externalIP       = externalIP;
        this.folderPath       = folderPath;
        this.filesInDir       = filesInDir;
        this.ds               = ds;
        this.ftr              = ftr;
        this.readLock         = readLock;
        this.writeLock        = writeLock;
        this.requestsSent     = requestsSent;
        this.requestsReceived = requestsReceived;
    }

    //TODO: add locks to the data structures
    //TODO: Make a thread that resends the write requests not confirmed
    //TODO: Block the threads from receiving or sending if the limit is reached

    @Override
    public void run() {
        if (receiver) receive();
        else sendWriteRequest();
    }

    public void sendWriteRequest() {
        Map.Entry<String,Long> entry; String filename; Long date;
        DatagramSocket datagramSocket; short port;
        Iterator<Map.Entry<String,Long>> it = this.filesInDir.entrySet().iterator();

        boolean requestSent = true;

            while(it.hasNext()) {
                entry = it.next(); filename = entry.getKey(); date = entry.getValue();

                //Gets local usable port
                datagramSocket = createDatagramSocket();
                while (datagramSocket == null) {
                    //Sleeps 1 second and tries to get a valid socket again
                    try { sleep(500); } catch (InterruptedException ignored) {}
                    datagramSocket = createDatagramSocket();
                }
                port = (short) datagramSocket.getLocalPort();
                
                while(Thread.activeCount() >= FFSync.MAXTHREADSNUMBER)
                    try { sleep(500); } catch (InterruptedException ignored) {} //Maybe use 'Signal'

                try {
                    writeLock.lock();
                    ftr.requestRRWR(filename, port, (short) 2, date);
                } catch (OpcodeNotRecognizedException | IOException e) {
                    requestSent = false;
                } finally { writeLock.unlock(); }

                if(requestSent) {
                    TransferWorker tw = new TransferWorker(true, false, folderPath, filename, datagramSocket);
                    requestsSent.put(filename, tw);

                    it.remove(); //Removes from the "queue"
                }

                requestSent = true;
            }


            //TODO: Add check of number of threads
            //TODO: If transferworker(receiver) gets a timeout before starting the receive of a file, change its state to TIMEDOUT, so it can be rerunned after receiving the request again 
            //TODO: Use timer between iterations?
            for(int i = 0; i < 5 && filesInDir.size() != 0; i++){
                TransferWorker transferWorker = null;

                for(Map.Entry<String,TransferWorker> requestSentEntry : requestsSent.entrySet()){
                    transferWorker = requestSentEntry.getValue();
                    if(transferWorker.getTWState() == TransferWorker.TWState.NEW){
                        try {
                            writeLock.lock();
                            ftr.requestRRWR(transferWorker.getFileName(), transferWorker.getLocalPort(), (short) 2, (long) 0);
                        } catch (OpcodeNotRecognizedException | IOException ignored) {
                        } finally { writeLock.unlock(); }
                    }
                }
            }

            System.out.println("Já terminei de enviar tudo!");
    }

    public void receive() {
        DatagramPacket dp     = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean receive       = true, //Used as a flag that indicates if the thread should continue to listen for packets
                handlePackage = true; //Flag that indicates if the package should be handled

        //Tries to receive packets until the throw of a SocketTimeoutException along with half of the threads disponibilized for receiving not being used
        while (receive) {

            try {
                readLock.lock();
                ds.receive(dp);
            } catch (SocketTimeoutException s) {
                if(Thread.activeCount() < (FFSync.MAXTHREADSNUMBER / 2)) receive = false;
                //if(FFSync.CURRENTRECEIVERSNUMBER < (FFSync.MAXTHREADSNUMBERPERFUNCTION / 2)) receive = false;
            } catch (IOException ioException) {
                handlePackage = false;
            } finally {
                readLock.unlock();
            }

            if (handlePackage && receive) {
                if (ftr.getOpcode(dp.getData()) == FTrapid.WRopcode)
                    receiveWriteRequest(dp);
                else if (ftr.getOpcode(dp.getData()) == FTrapid.SYNopcode)
                    receiveSyn(dp);
                else if (ftr.getOpcode(dp.getData()) == FTrapid.ERRopcode)
                    receiveError(dp);
            }

            handlePackage = true;
        }
    }


    /* ********** Auxiliar Methods ********** */

    private void receiveWriteRequest(DatagramPacket dp) {
        short msg, errorOrSyn /* 1 - error ; 2 - syn */;
        DatagramSocket dsTransferWorker;
        RequestPackageInfo rpi;

        try {
            rpi = ftr.analyseRequest(dp);
            String filename = rpi.getFilename();

            //New Request. The file is not being received nor was it received.
            if (!requestsReceived.containsKey(filename)) {
                //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests
                dsTransferWorker = createDatagramSocket();
                TransferWorker tw = new TransferWorker(false, true, folderPath, filename, dsTransferWorker);
                tw.connectToPort(externalIP, rpi.getPort());

                while(Thread.activeCount() >= FFSync.MAXTHREADSNUMBER)
                    try { sleep(500); } catch (InterruptedException ignored) {} //Maybe use 'Signal'

                tw.start();

                //TODO: Provavelmente vai ser necessário adicionar locks para as estruturas de dados
                requestsReceived.put(filename, tw);
                errorOrSyn = 2;
                msg        = (short) dsTransferWorker.getLocalPort(); System.out.println("request- filename" + filename + " /port sent in syn: " + msg);
            } else {
                //Duplicate of a previous request. Resends SYN package.
                errorOrSyn = 2;
                msg        = requestsReceived.get(filename).getLocalPort(); System.out.println("dup request- filename" + filename + " /port sent in syn: " + msg);
            }

            //Sends answer
            try {
                writeLock.lock();
                ftr.answer(errorOrSyn, msg, filename);
            } catch (OpcodeNotRecognizedException | IOException ignored) { //Tries again after a resend, in case of a IOException
            } finally { writeLock.unlock(); }
        } catch (IntegrityException ignored) {} //Doesnt do anything. Expects a resend.
    }

    private void receiveSyn(DatagramPacket dp) {
        ErrorSynPackageInfo espi = null;
        try {
            espi = ftr.analyseAnswer(dp);

            String filename = espi.getFilename();
            TransferWorker tw;

            //If there is a transfer worker associated with the file name received, starts it(only if it isnt running already)
            //Receive of SYN duplicate doesnt affect the application
            if ((tw = requestsSent.get(filename)) != null) {
                if (!tw.isAlive() && tw.getTWState() == TransferWorker.TWState.NEW) {
                    tw.connectToPort(externalIP, espi.getMsg());

                    while(Thread.activeCount() >= FFSync.MAXTHREADSNUMBER)
                    try { sleep(500); } catch (InterruptedException ignored) {} //Maybe use 'Signal'

                    tw.start();
                }
            }
            //Sends an error if there isnt a "log" of a thread in charge of sending a file with the name received
            else {
                try {
                    writeLock.lock();
                    ftr.answer((short) 1, (short) 403, filename);
                } catch (OpcodeNotRecognizedException | IOException ignored) {
                } finally { writeLock.unlock(); }
            }
        } catch (IntegrityException | OpcodeNotRecognizedException e) {
            //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
        }
    }

    //TODO: Add all the errors and handled them (Maior parte é simplesmente um log a dizer que houve erro provavelmente)
    private void receiveError(DatagramPacket dp) {
        ErrorSynPackageInfo espi;
        try {
            espi = ftr.analyseAnswer(dp);

            short errorCode = espi.getMsg();
            String filename = espi.getFilename();

            //Connection error
            if (errorCode == 400) {
                //TODO: Maybe throw exception to end the program
                //throw new ConnectException();
            } else if (errorCode == 401) {
                //Remover o ficheiro na maquina que recebe
            } else if (errorCode == 402) {

            }
        } catch (IntegrityException | OpcodeNotRecognizedException e) {
            //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
        }
    }

    /*
    Returns DatagramSocket with the first non-privileged available port.
    Returns null if there isnt an available port
     */
    private DatagramSocket createDatagramSocket() {
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

    //TODO: implementar locks para fazer isto. Reutilizar isto para percorrer o map de requests e mandar novamente os requests
    public void cleanTerminatedWorkers(Map<String,TransferWorker> requests){
        for(Map.Entry<String,TransferWorker> entry : requests.entrySet()){
            if(entry.getValue() != null && entry.getValue().getTWState() == TransferWorker.TWState.TERMINATED) {
                try {
                    entry.getValue().join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                requestsSent.replace(entry.getKey(),null);
            }
        }
    }
}
