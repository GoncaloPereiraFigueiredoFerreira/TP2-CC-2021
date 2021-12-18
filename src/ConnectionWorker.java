import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectionWorker extends Thread {
    private final int TIMEOUT = 10000;
    private final DatagramSocket ds;
    private final FTrapid ftr;
    private final SharedInfo si;

    public ConnectionWorker(DatagramSocket ds, FTrapid ftr, SharedInfo si) {
        this.ds  = ds;
        this.ftr = ftr;
        this.si  = si;
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
            //Set timeout
            try {
                ds.setSoTimeout(TIMEOUT);
            } catch (SocketException e) {
                si.writeToLogFile("REQUEST SOCKET (ERROR): Error changing socket settings!");
            }

            //Receive Package
            try {
                si.receiveRequestsLock.lock();
                ds.receive(dp);
            } catch (SocketTimeoutException s) {
                if (si.receivers.activeCount() < (FFSync.getMAXTHREADSNUMBERPERFUNCTION() / 2)) {
                    System.out.println("Parei de receber requests: " + LocalDateTime.now());
                    receive = false;
                }else{
                    while (si.receivers.activeCount() >= FFSync.getMAXTHREADSNUMBERPERFUNCTION()) {
                        try { si.receiveRequestsCond.await(); }
                        catch (InterruptedException ignored) {}
                    }
                }
            } catch (IOException ioException) {
                handlePackage = false;
            } finally {
                si.receiveRequestsLock.unlock();
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
            if (!si.status.wasRequestReceived(filename)) {
                dsTransferWorker = FFSync.createDatagramSocket();
                TransferWorker tw = new TransferWorker(si.receivers, false, true, filename, dsTransferWorker, rpi.getPort(), si);
                tw.start();
                si.status.addRequestReceived(filename,tw);
            }
        } catch (IntegrityException ignored) {System.out.println("Integrity/Opcode receiveWriteReq");} //Expects a resend.
    }

    private void receiveError(DatagramPacket dp) {
        ErrorSynPackageInfo espi;
        try {
            espi = ftr.analyseAnswer(dp);

            short errorCode = espi.getMsg();
            String filename = espi.getFilename();

            //Connection error
            if (errorCode == 400) {
                //throw new ConnectException();
            } else if (errorCode == 401) {
                //Remover o ficheiro na maquina que recebe
            } else if (errorCode == 402) {

            }
        } catch (IntegrityException | OpcodeNotRecognizedException e) {}
        //OpcodeNotRecognizedException doesn't happen in here. Checked in the caller function
    }
}