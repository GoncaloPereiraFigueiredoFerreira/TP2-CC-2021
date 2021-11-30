import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;


public class FTrapid {

    //Maybe
    private DatagramSocket dS;
    private final byte RDopcode = 1;
    private final byte WRopcode = 2;
    private final byte DATAopcode = 3;
    private final byte ACKopcode = 4;
    private final byte ERRopcode = 5;
    private final byte SYNopcode = 6;

    private final int MAXRDWRSIZE=514; // n sei
    private final int MAXDATASIZE=1024;
    private final int MAXDATA = 1019;
    private final int MAXACKSIZE=3;
    private final int MAXERRORSIZE=3;
    private final int MAXSYNSIZE=3;


    //DatagraSocket Sender Size =  65535 B ≃ 64KB
    //DatagraSocket Receiver Size = 2147483647 B ≃ 2.00 GB

    /////////////////////// Definição dos pacotes///////////////////////////

    /*
     *   PACOTE DE RD/WR:
     *
     *   OPCODE = 1 / 2
     *
     *       1B       2B          String         1B
     *   | opcode |  Porta  |     Filename    |  0  |
     *
     *
     *
     *
     */

    private byte[] createRDWRPackage(String filename,short opcode, short port){
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(2+ filename.length());
        if (opcode==1) out.put(RDopcode);
        else if (opcode==2) out.put(WRopcode);
        // else sendException
        out.putShort(port);
        out.put(filename.getBytes(StandardCharsets.UTF_8));
        out.put((byte) 0);
        packet = out.array();
        return packet;
    }

    /*
    *   PACOTE DE DATA:
    *
    *   OPCODE = 3
    *
    *       1B        2B        2B            N Bytes < 1019
    *   | opcode | nº bloco |  length    |        DATA         |
    *
    *   Não precisa de 0?
    *
    *
     */
    private byte[][] createDATAPackage(byte[] data) {
        int numberPackts = data.length / MAXDATA;
        if (data.length % MAXDATA != 0) numberPackts++; //tamanho maximo = 512, lgo como divInteira arredonda pra baixo
        // somamos mais 1 se o resto n for 0

        byte[][] packets = new byte[numberPackts][];

        short packetLength;
        int ofset=0;
        for (short i = 0; i < numberPackts; i++) {

            // packetLength max = MAXDATA
            packetLength = (short) Integer.min(data.length- ofset,MAXDATA);

            //header de DATA = 5
            ByteBuffer out = ByteBuffer.allocate(5+packetLength);
            out.put(DATAopcode);
            out.putShort(i);
            out.putShort(packetLength);
            out.put(data,ofset,packetLength);
            ofset+=packetLength;
            packets[i]=out.array();


        }
        return packets;
    }


    /*
     *   PACOTE DE ACK:
     *
     *  OPCODE = 4
     *
     *       1B        2B
     *   | opcode | nº bloco |
     *
     */

    private byte[] createACKPackage(short block) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(3);
        out.put((byte) 4);
        out.putShort(block);
        packet = out.array();
        return packet;
    }

    /*
     *   PACOTE DE ERROR:
     *
     *  OPCODE = 5
     *
     *       1B        2B
     *   | opcode | Error Code |
     *
     *  Será que precisa da String "Error Msg" ??
     *
     */
    private byte[] createERRORPackage(short error) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXERRORSIZE);
        out.put(ERRopcode);
        out.putShort(error);
        packet = out.array();
        return packet;
    }

    /*
     *   PACOTE DE SYN:
     *
     *  OPCODE = 6
     *
     *       1B          2B
     *   | opcode |     Port      |
     *
     *  Pacote para estabelecimento de conexão
     *
     */

    private byte[] createSYNPackage(short port) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(MAXSYNSIZE);
        out.put(SYNopcode);
        out.putShort(port);
        packet = out.array();
        return packet;
    }

    ///////////////////////// Interpreta Packets ////////////////////////////7

    /*
    *
    * Interpreta pacotes de RD/WR
    *
    */

    private RequestPackageInfo readRDWRPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String ret = null;
        short port = -1;

        int length=0;
        if (out.get(0) == 1 || out.get(0)==2) {
            port = out.getShort(1);
            out.position(3);
            while(out.get() != (byte) 0) length++;
            byte[] temp = new byte[length];
            out.get(temp, 1, length);
            ret = new String(temp, StandardCharsets.UTF_8);
        }
        // else Exception

        return new RequestPackageInfo(out.get(0),port,ret);
    }


    /*
     *
     * Intrepreta pacotes de DATA
     *
     */
    private DataPackageInfo readDataPacket(byte[] data){
        ByteBuffer out = ByteBuffer.allocate(MAXDATA);
        out.put(data,0,data.length);
        out.position(0);
        byte opcode = out.get(0);
        byte[] msg=null;
        DataPackageInfo par = null;

        if (opcode == 3){

            short block = out.getShort();
            short length = out.getShort();

            ByteBuffer tmp = ByteBuffer.allocate(length);
            tmp.put(data,5,length);
            msg=tmp.array();

            par = new DataPackageInfo(block,msg);

        }
        return par;
    }

    /*
     *
     * Intrepreta pacotes de ACK
     *
     */

    private short readACKPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        short ret = -1;
        if (out.get() == 1 || out.get()==2) {
            ret = out.getShort();
        }
        // else Exception
        return ret;
    }

    /*
     *
     * Intrepreta pacotes de ERROR
     *
     */

    private short readERRSYNPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        short ret = -1;
        if (out.get(0) == 5 || out.get(0)==6) {
            ret = out.getShort(1);
        }
        // else Exception
        return ret;
    }

    ///////////////////////////////////Public Methods//////////////////////////////////



    ///////////////////////// Transmition Control ///////////////////////

        //////////Methods for workers/////
    /*     * SEND:
     *
     *     1. send data
     *     2. wait for ack
     *     3. verify size, se o size < 514, transmition over, senao: 2.
     *
     *     1º Versão sequencial para testes
     *     - O protocolo deverá enviar os pacotes e verificar acks ao msm tempo
     */

    public int sendData(byte[] msg){
        //1º convert msg to packets
        byte [][] packetsData = createDATAPackage(msg);

        //2º start loop of transfer
        boolean flag = true;
        int npackets = packetsData.length;
        for (short i =0; flag;){
            if (packetsData[i].length < MAXDATASIZE) flag = false;
            DatagramPacket dPout = new DatagramPacket(packetsData[i],packetsData[i].length);
            try {
                dS.send(dPout);
            } catch (IOException e) {}

            // 3º Esperar por ACK // Esta parte deveria ser feita por outra thread
            DatagramPacket dPin = new DatagramPacket(new byte[3],3);
            try {
                //fica locked em caso de não receber nada (deveria ter um timeout)
                dS.receive(dPin);
            } catch (IOException e) {}

            // 4º Traduzir Ack
            if (this.verifyPackage(dPin.getData())==4) {
                short packet = this.readACKPacket(dPin.getData());
                if (packet == i) i++;
            }
        }
        return 0;
    }


    /*     * RECEIVE :
     *
     *     1. timeout for DATA
     *     2. receive DATA
     *     3. send ACK
     *     4. verify size, se o size < 514, transmition over, senao: 1.
     */
    public byte[] receiveData() {
        byte[] msg;
        byte [][] packets = new byte[32766][];
        short lastblock=0;
        boolean flag = true;
        DataPackageInfo info = null;

        // 1º Start loop
        while(flag){
            DatagramPacket dPin = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);

            try {
                //fica locked em caso de não receber nada (deveria ter um timeout)
                dS.receive(dPin);
            } catch (IOException e) {}



            // 2º Verificar Package Recebido e guardar
            if (verifyPackage(dPin.getData()) == 3){
                info = this.readDataPacket(dPin.getData());
                if (info.getData().length < MAXDATASIZE) {flag = false; lastblock = (short) info.getData().length;}
                packets[info.getNrBloco()]=info.getData();
            }
            //else exception

            // 3º Send ACK
            DatagramPacket dPout = new DatagramPacket(createACKPackage(info.getNrBloco()),MAXACKSIZE);
            try {
                dS.send(dPout);
            } catch (IOException e) {}

        }
        //preciso de verificar quais os pacotes que faltam
        int block =0;

        //verificação super simples que pode n funcionar se todos os pacotes n tiverem sido enviados
        for(; packets[block]!=null;block++);


        ByteBuffer b = ByteBuffer.allocate(MAXDATA*(block-1) + lastblock);
        for(int i =0; i<block; i++) b.put(packets[i]);
        msg= b.array();
        return msg;
    }



    ////////Methods for main Port////////////

    public int requestRRWR(String filename,short port,short mode){
        byte[] readRequest =null;
        if (mode == 1) createRDWRPackage(filename,RDopcode,port);
        else if (mode == 2) createRDWRPackage(filename,WRopcode,port);
        boolean flag=true;
        //else exception
        byte[] msg = null;
        int ret=0;

        while(flag) {

            try {
                dS.send(new DatagramPacket(readRequest, readRequest.length));
            } catch (IOException e) {
            }

            try {
                //receives either an error or a SYN
                msg = new byte[MAXERRORSIZE];
                dS.receive(new DatagramPacket(msg, MAXERRORSIZE));
            } catch (IOException e) {
            }

            if (msg.length > 0) { // se n houve timeouts
                //analyse the msg to recognize any errors and get port
                int opcode = verifyPackage(msg);
                ret = readERRSYNPacket(msg);
                if (opcode == 5) ret = -ret;
                flag = false;
            }
        }
        return ret;
    }


    public RequestPackageInfo analyseRequest(DatagramPacket dp){
      return readRDWRPacket(dp.getData());
   }

    public int answer(short mode,short msg){
       // mode is 1 for error msg or 2 for syn
       // msg is either an error code or a port number
       byte[] packet=null;
       if (mode == 1) packet= createERRORPackage(msg);
       else if (mode ==2) packet = createSYNPackage(msg);
       //else exception

       try {
           dS.send(new DatagramPacket(packet,packet.length));
       } catch (IOException e) {}

       return 0;

   }

    public short verifyPackage(byte[] data){
        //ByteBuffer out = ByteBuffer.allocate(length);
        //out.put(data,0,length);
        //out.position(0);
        byte opcode = data[0];
        return opcode;
    }

}
