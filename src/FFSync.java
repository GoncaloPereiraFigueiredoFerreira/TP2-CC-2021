import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class FFSync {

    public static void main(String[] args) {
        //args[0] is the name of the folder to be shared
        //args[1] is the IP adress of the computer ...

        //****************** tirar este pedaço adicionado para testes ******************
        Scanner sc = new Scanner(System.in);
        System.out.print("Pasta:");
        args[0]=sc.next();
        System.out.print("IP:");
        args[1]=sc.next();

        //Socket used to handle requests
        DatagramSocket ds;

        try {
            ds = new DatagramSocket(12346);
            ds.connect(InetAddress.getByName(args[1]),12346);
            String oi = "es gay";

            try {
                ds.send(new DatagramPacket(oi.getBytes(StandardCharsets.UTF_8),oi.getBytes(StandardCharsets.UTF_8).length));
            } catch (IOException e) {
                e.printStackTrace();
            }

            byte[] arr = new byte[64];
            try {
                ds.receive(new DatagramPacket(arr,64));
            } catch (IOException e) {
                e.printStackTrace();
            }
            String s = new String(arr,StandardCharsets.UTF_8);
            System.out.println(s);
        } catch (SocketException | UnknownHostException e) {
            //*************** lembrar de lidar com estas excecoes *****************
            e.printStackTrace();
        }

    }

}
