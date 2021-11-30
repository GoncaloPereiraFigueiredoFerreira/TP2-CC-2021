import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;


public class FTrapid {

    //Maybe
    private DatagramSocket dS;
    private final byte RDopcode = 1;
    private final byte WRopcode = 2;
    private final byte DATAopcode = 3;
    private final byte ACKopcode = 4;
    private final byte ERRopcode = 5;

    private final int MAXRDWRSIZE=514; // n sei
    private final int MAXDATASIZE=1024;
    private final int MAXDATA = 1019;
    private final int MAXACKSIZE=3;
    private final int MAXERRORSIZE=3;

    //DatagraSocket Sender Size =  65535 B ≃ 64KB
    //DatagraSocket Receiver Size = 2147483647 B ≃ 2.00 GB

    /////////////////////// Definição dos pacotes///////////////////////////

    /*
     *   PACOTE DE RD/WR:
     *
     *   OPCODE = 1 / 2
     *
     *       1B        String       1B
     *   | opcode |   Filename    |  0  |
     *
     *   Precisa do mode?
     *   Precisa do 0?
     *
     */

    public byte[] createRDWRPackage(String filename,short opcode){
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(2+ filename.length());
        if (opcode==1) out.put(RDopcode);
        else if (opcode==2) out.put(WRopcode);
        // else sendException
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
    public byte[][] createDATAPackage(byte[] data) {
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

    public byte[] createACKPackage(short block) {
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
    public byte[] createERRORPackage(short error) {
        byte[] packet;
        ByteBuffer out = ByteBuffer.allocate(3);
        out.put((byte) 5);
        out.putShort(error);
        packet = out.array();
        return packet;
    }

    ///////////////////////// Intrepreta Packets ////////////////////////////7

    /*
    *
    * Intrepreta pacotes de RD/WR
    *
    */

    public String readRDWRPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        out.put(packet);
        String ret = null;
        int length=0;
        if (out.get(0) == 1 || out.get(0)==2) {
            out.position(1);
            while(out.get() != (byte) 0) length++;
            byte[] temp = new byte[length];
            out.get(temp, 1, length);
            ret = new String(temp, StandardCharsets.UTF_8);
        }
        // else Exception
        return ret;
    }


    /*
     *
     * Intrepreta pacotes de DATA
     *
     */
    public DataPackageInfo readDataPacket(byte[] data){
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

    public short readACKPacket(byte[] packet){
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

    public short readERRORPacket(byte[] packet){
        ByteBuffer out = ByteBuffer.allocate(packet.length);
        short ret = -1;
        if (out.get() == 1 || out.get()==2) {
            ret = out.getShort();
        }
        // else Exception
        return ret;
    }

    /*
    * Verifica o pacote e retorna o opcode deste.
    *
    * Como fazer verificação do pacote?
    */
    public short verifyPackage(byte[] data){
        //ByteBuffer out = ByteBuffer.allocate(length);
        //out.put(data,0,length);
        //out.position(0);
        byte opcode = data[0];
        return opcode;
    }


    ///////////////////////// Transmition Control ///////////////////////
    /*     * SEND:
     *
     *     1. send data
     *     2. wait for ack
     *     3. verify size, se o size < 514, transmition over, senao: 2.
     *
     *     1º Versão sequencial para testes
     *     - O protocolo deverá enviar os pacotes e verificar acks ao msm tempo
     */

    public int send(byte[] msg){
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
    public byte[] receive() {
        byte[] msg;
        byte [][] packets = new byte[32766][];
        short lastblock=0;
        boolean flag = true;
        DataPackageInfo info = null;

        // 1º Start loop
        while(flag){
            DatagramPacket dPin = new DatagramPacket(new byte[MAXDATASIZE],MAXDATASIZE);

            try {
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

        //verificaçao super simples que pode n funcionar se todos os pacotes n tiverem sido enviados
        for(; packets[block]!=null;block++);


        ByteBuffer b = ByteBuffer.allocate(MAXDATA*(block-1) + lastblock);
        for(int i =0; i<block; i++) b.put(packets[i]);
        msg= b.array();
        return msg;
    }

    public int requestRR(){
        /* open socket
        * dS.connect(ip,port)
        * check connection
        * send RR
        * receive()
        */
        return 0;
    }

    public int requestWR(){
        /*
         * check connection
         * send :
         *     1. WR packet
         *     2. timeout for ACK
         *     3. send DATA
         *     4. timeout for ACk
         *     ... (ate o enviado ser menor do que 514)
         *
         *
         */
        return 0;
    }

    public int answerWR(){
        /*
        *   receive WR
        *   check file
        *   receive:
        *      1. send Ack
        *      2. timeout for DATA
        *      3. receive DATA
        *      4. verify size, se o size < 514, transmition over, senao: 1.
        */
        return 0;
    }

    public int answerRD(){
        /*
         *   receive RD
         *   check file
         *   send:
         *      1. send DATA
         *      2. timeout for ACK
         *      3. receive DATA
         *      4. verify size, se o size < 514, transmition over, senao: 1.
         */
        return 0;
    }




    public static void main(String[] ar){
        FTrapid x = new FTrapid();
        byte [] tmp = x.createDATAPackage("ola".getBytes(StandardCharsets.UTF_8))[0];
        System.out.println("Tamnho: " + tmp.length);
        x.readDataPacket(tmp);

        byte[] msg = "ola".getBytes(StandardCharsets.UTF_8);
        String s = new String(msg,StandardCharsets.UTF_8);

        System.out.println(s);


    }
}
