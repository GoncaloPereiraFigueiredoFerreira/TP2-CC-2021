import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionWorker extends Thread {
    private final String externalIP; //IP of the other client
    private final String folderPath;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final ReentrantLock receiveLock;
    private final ReentrantLock sendLock;
    private final Map<String, TransferWorker> requestsReceived; //Keeps track of the files received and the files that are being received
    private final ThreadGroup receivers;

    public ConnectionWorker(ThreadGroup receivers, String externalIP, String folderPath, DatagramSocket ds, FTrapid ftr, ReentrantLock receiveLock, ReentrantLock sendLock, Map<String, TransferWorker> requestsReceived) {
        this.receivers = receivers;
        this.externalIP = externalIP;
        this.folderPath = folderPath;
        this.ds = ds;
        this.ftr = ftr;
        this.receiveLock = receiveLock;
        this.sendLock = sendLock;
        this.requestsReceived = requestsReceived;
    }


    @Override
    public void run() {
        receive();
    }


    public void receive() {
        DatagramPacket dp     = new DatagramPacket(new byte[FTrapid.MAXRDWRSIZE], FTrapid.MAXRDWRSIZE);
        boolean receive       = true, //Used as a flag that indicates if the thread should continue to listen for packets
                handlePackage = true; //Flag that indicates if the package should be handled

        //Tries to receive packets until the throw of a SocketTimeoutException along with half of the threads disponibilized for receiving not being used
        while (receive) {
            try {
                receiveLock.lock();
                ds.receive(dp);
            } catch (SocketTimeoutException s) {
                if (receivers.activeCount() < (FFSync.getMAXTHREADSNUMBERPERFUNCTION() / 2)) receive = false;
            } catch (IOException ioException) {
                handlePackage = false;
            } finally {
                receiveLock.unlock();
            }

            if (handlePackage && receive) {
                if (ftr.getOpcode(dp.getData()) == FTrapid.WRopcode)
                    receiveWriteRequest(dp);
                //else if (ftr.getOpcode(dp.getData()) == FTrapid.SYNopcode)
                //    receiveSyn(dp);
                else if (ftr.getOpcode(dp.getData()) == FTrapid.ERRopcode)
                    receiveError(dp);
            }

            handlePackage = true;
        }
    }


    /* ********** Auxiliar Methods ********** */

    private void receiveWriteRequest(DatagramPacket dp) {
        DatagramSocket dsTransferWorker;
        RequestPackageInfo rpi;

        try {
            rpi = ftr.analyseRequest(dp);
            String filename = rpi.getFilename();

            //New Request. The file is not being received nor was it received.
            //Ignores duplicates. Expects resends from the receiver (TransferWorker) created.
            if (!requestsReceived.containsKey(filename)) {
                dsTransferWorker = FFSync.createDatagramSocket();
                TransferWorker tw = new TransferWorker(receivers, false, true, folderPath, filename, dsTransferWorker, externalIP, rpi.getPort(), sendLock);

                //TODO: Adicionar sleep caso não hajam threads para responder às necessidades dos requests. N deve ser preciso, o outro cliente tem lock para o numero de threads a enviar e o receive é bloqueante
                while (receivers.activeCount() >= FFSync.getMAXTHREADSNUMBERPERFUNCTION()) {
                   try { sleep(500); }
                   catch (InterruptedException ignored) {}
                }

                tw.start();

                //TODO: Provavelmente vai ser necessário adicionar locks para as estruturas de dados
                requestsReceived.put(filename, tw);
            }
        } catch (IntegrityException ignored) {} //Expects a resend.
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
        } catch (IntegrityException | OpcodeNotRecognizedException e) {}
        //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
    }
}