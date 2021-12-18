
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
//import org.apache.commons.lang.StringEscapeUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class HTTPRequestsAnswerer extends Thread {
    private HttpServer server;
    private final String ip;
    private final SharedInfo si;

    public HTTPRequestsAnswerer(String ip, SharedInfo si){
        this.ip = ip; System.out.println("ip: " + ip);
        this.si = si;
    }

    public void run() {
        //HTTP server initialization
        try {
            server = HttpServer.create(new InetSocketAddress(ip, 8000), 0);
            server.createContext("/", new MyHandler(si));
            server.setExecutor(null); // creates a default executor
            server.start();
            si.writeToLogFile("HTTP SERVER: Initialized successfuly!");
        } catch (IOException ioException) {
            si.writeToLogFile("HTTP SERVER (ERROR): Could not be initialized!");
        }
    }

    public void stopServer(){
        server.stop(0);
    }

    private static class MyHandler implements HttpHandler{
        SharedInfo si;
        private final Map<TransferWorker.TWState,String> stateStringMap = new HashMap<>();

        public MyHandler(SharedInfo si){
            this.si = si;

            stateStringMap.put(TransferWorker.TWState.NEW,"Waiting for answer!");
            stateStringMap.put(TransferWorker.TWState.RUNNING,"Running!");
            stateStringMap.put(TransferWorker.TWState.TERMINATED,"Finished!");
            stateStringMap.put(TransferWorker.TWState.TIMEDOUT,"Timed out!");
            stateStringMap.put(TransferWorker.TWState.ERROROCURRED,"Error ocurred!");
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = statusToString();
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.flush();
            os.close();
        }



        /* ******* Auxiliar Methods ******* */

        public String statusToString(){
            StringBuilder sb = new StringBuilder();

            //Add files in queue to the string
            Collection<String> filesToBeSent = si.status.getFilesToBeSent();
            sb.append("Files in queue:\n\n");
            int i = 1;
            for(String filename : filesToBeSent){
                sb.append(i).append(") ").append(filename).append("\n");
                i++;
            }
            filesToBeSent = null;

            sb.append("\n\n");

            // Add info of requests sent
            Collection<TransferWorker> requestsSent = si.status.getRequestsSent();
            sb.append("Requests Sent:\n\n");
            addStatusRequestsToString(sb, requestsSent);
            requestsSent = null;

            sb.append("\n\n");

            //Add info of requests received
            Collection<TransferWorker> requestsReceived = si.status.getRequestsReceived();
            sb.append("Requests Received:\n\n");
            addStatusRequestsToString(sb, requestsReceived);
            requestsReceived = null;

            return sb.toString();
        }

        public void addStatusRequestsToString(StringBuilder sb, Collection<TransferWorker> collection){
        /* Add info of requests:
            -> File Name
            -> State of the request
            -> Time occured since the start of transference, if the request is running
            -> Transfer time, if the request has terminated
         */
            TransferWorker.TWState twState;

            for(TransferWorker tw : collection) {
                twState = tw.getTWState();
                sb.append("-> ").append("Name: ").append(tw.getFileName()).append("   ")
                        .append("State: ").append(stateStringMap.get(twState)).append("   ");

                if(twState == TransferWorker.TWState.RUNNING){
                    sb.append("Time Ocurred: ");
                    if(tw.getTransferStartTime() != null) {
                        double transferTime = ((double) (System.nanoTime() - tw.getTransferStartTime()) / (double) 1000000000);
                        sb.append(String.format("%.4f", transferTime));
                    }
                    else sb.append("-");
                }
                else if (twState == TransferWorker.TWState.TERMINATED){
                    sb.append("Transfer Time: ").append(String.format("%.4f",tw.getTransferTime()));
                }

                sb.append("\n");
            }
        }

    }


   /* public static void Html_princ(Map<Integer,String> info, String ip) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(ip, 8001), 0);

        server.createContext("/", new MyHttpHandler(info));

        server.start();
    }

    static class MyHttpHandler implements HttpHandler {

        Map<Integer, String> info;

        public MyHttpHandler(Map<Integer,String> info) {
            this.info = info;
        }

        public void handle(HttpExchange httpExchange) throws IOException {

            handleResponse(httpExchange, info);
        }

        private void handleResponse(HttpExchange httpExchange, Map<Integer,String> info) throws IOException {

            OutputStream outputStream = httpExchange.getResponseBody();

            StringBuilder htmlBuilder = new StringBuilder();


            //htmlBuilder.append(info).append(info[1]);


            String htmlResponse = StringEscapeUtils.escapeHtml(htmlBuilder.toString());

            httpExchange.sendResponseHeaders(200, htmlResponse.length());

            outputStream.write(htmlResponse.getBytes());

            outputStream.flush();

            outputStream.close();

        }
    }*/

}