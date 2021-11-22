import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;


public class ftprapid {

    public byte[][] createDataPackage(byte[] data,int length) {
        int numberPackts = length / 512;
        if (length % 512 != 0) numberPackts++; //tamanho maximo = 512, lgo como divInteira arredonda pra baixo
        // somamos mais 1 se o resto n for 0

        byte[][] packets = new byte[numberPackts][];

        int packetLength,ofset=0;
        for (int i = 0; i < numberPackts; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(3);
            out.write(i);
            if (length- ofset > 512){
               packetLength=512;
            }
            else {
                packetLength= length-ofset;
            }
            out.write(data, ofset, packetLength);
            ofset+=packetLength;
            System.out.println(out.toByteArray());
            packets[i]=out.toByteArray();
            
        }
        return packets;
    }
    public void readDataPackage(byte[] data, int length){
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        byte[] ret = new byte[length];
        in.read(ret,0,length);
        System.out.println(ret);
    }




    public byte[][] createDataPackage2(byte[] data,int length) {
        int numberPackts = length / 512;
        if (length % 512 != 0) numberPackts++; //tamanho maximo = 512, lgo como divInteira arredonda pra baixo
        // somamos mais 1 se o resto n for 0

        byte[][] packets = new byte[numberPackts][];

        int packetLength,ofset=0;
        for (short i = 0; i < numberPackts; i++) {

            if (length- ofset > 512){
                packetLength=512;
            }
            else {
                packetLength= length-ofset;
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

    public void readDataPackage2(byte[] data, int length){
        ByteBuffer out = ByteBuffer.allocate(length);
        out.put(data,0,length);
        //out.compact();
        out.position(0);
        byte opcode = out.get();
        System.out.println("opcode: "+opcode);
        short block = out.getShort();
        System.out.println("Block: "+ block);
        System.out.flush();
        ByteBuffer tmp = ByteBuffer.allocate(length-3);
        tmp.put(out);
        byte[] msg=tmp.array();
        String s = new String(msg,StandardCharsets.UTF_8);
        System.out.println(s);
    }



    public static void main(String[] ar){
        ftprapid x = new ftprapid();
        byte [] tmp = x.createDataPackage2("ola".getBytes(StandardCharsets.UTF_8),"ola".getBytes(StandardCharsets.UTF_8).length)[0];
        System.out.println("Tamnho: " + tmp.length);
        x.readDataPackage2(tmp,tmp.length);

        byte[] msg = "ola".getBytes(StandardCharsets.UTF_8);
        String s = new String(msg,StandardCharsets.UTF_8);

        System.out.println(s);


    }
}
