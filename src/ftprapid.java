import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;


public class ftprapid {

    //Maybe
    // private DatagramSocket dS;

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
        if (opcode==1) out.put((byte) 1);
        else if (opcode==2) out.put((byte) 2);
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
    *       1B        2B            N Bytes
    *   | opcode | nº bloco |        DATA         |
    *
    *   Não precisa de 0?
    *
    *
     */
    public byte[][] createDATAPackage(byte[] data) {
        int numberPackts = data.length / 512;
        if (data.length % 512 != 0) numberPackts++; //tamanho maximo = 512, lgo como divInteira arredonda pra baixo
        // somamos mais 1 se o resto n for 0

        byte[][] packets = new byte[numberPackts][];

        int packetLength,ofset=0;
        for (short i = 0; i < numberPackts; i++) {

            if (data.length- ofset > 512){
                packetLength=512;
            }
            else {
                packetLength= data.length-ofset;
            }
            ByteBuffer out = ByteBuffer.allocate(3+packetLength);
            out.put((byte) 3);
            out.putShort(i);
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
        String ret = null;
        if (out.get() == 1 || out.get()==2) {
            byte[] temp = new byte[packet.length - 2];
            out.get(temp, 1, packet.length - 2);
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

    public byte[] readDataPacket(byte[] data){
        ByteBuffer out = ByteBuffer.allocate(data.length);
        out.put(data,0,data.length);
        out.position(0);
        byte opcode = out.get();
        byte[] msg=null;
        if (opcode == 3){
        //System.out.println("opcode: "+opcode);
        short block = out.getShort();
        //System.out.println("Block: "+ block);
        //System.out.flush();
        ByteBuffer tmp = ByteBuffer.allocate(data.length-3);
        tmp.put(out);
        msg=tmp.array();

        //String s = new String(msg,StandardCharsets.UTF_8);
        //System.out.println(s);
        }
        return msg;
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

    public int send(byte[] msg){
        return 0;
    }

    public int receive(byte[] msg){
        return 0;
    }

    public int requestRR(){
        /* open socket
        * dS.connect(ip,port)
        * check connection
        * receive :
        *     1. RR packet
        *     2. timeout for DATA
        *     3. receive DATA
        *     4. send ACK
        *     5. verify size, se o size < 514, transmition over, senao: 2.
        *
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
        ftprapid x = new ftprapid();
        byte [] tmp = x.createDATAPackage("ola".getBytes(StandardCharsets.UTF_8))[0];
        System.out.println("Tamnho: " + tmp.length);
        x.readDataPacket(tmp);

        byte[] msg = "ola".getBytes(StandardCharsets.UTF_8);
        String s = new String(msg,StandardCharsets.UTF_8);

        System.out.println(s);


    }
}
