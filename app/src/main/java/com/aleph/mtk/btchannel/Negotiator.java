package com.aleph.mtk.btchannel;

import android.bluetooth.BluetoothSocket;
import android.net.wifi.WifiConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * Created by MTK07942 on 3/13/2015.
 */

enum SState{
    INIT, HANDSHAKE, EXCHANGING, CHECKING, SEND_RESULT, WAIT_ACK
}

public class Negotiator extends Thread {

    private SState state;
    private boolean running;
    private BluetoothSocket socket;
    private String buffer;
    private InputStreamReader is;
    private PrintStream ps;
    private BufferedReader br;

    //basic info
    private WifiConfiguration apconfig;

    HandoffServer hserver;

    public Negotiator(HandoffServer s, BluetoothSocket _socket, WifiConfiguration config){
        running = false;
        hserver = s;
        socket = _socket;
        apconfig = config;

        state = SState.INIT;
    }

    public void run(){

        String result = "ACCEPT";
        long last, now;
        int retry = 0; //the retry number of each socket connection
        last = now = System.currentTimeMillis();

        running = true;
        //Accept successfully
        if(socket!=null) {

            try {
                is = new InputStreamReader( socket.getInputStream() );
                br = new BufferedReader(is);

                ps = new PrintStream( socket.getOutputStream() );

            } catch (IOException e){
                hserver.printui("NEGOTIATOR: ERROR: IO stream init fail.");
                System.out.println(e);
                return;
            }

            try {
                /***************************** Negotiator Main ************************************/
                while(running){

                    switch(state){
                        case INIT: //waiting for clients request, no timeout here
                            buffer = br.readLine();
                            hserver.printui("in INIT: rcv " + buffer);
                            if (buffer.equalsIgnoreCase("START_HANDSHAKE")) {
                                //check if proxy services are available
                                this.state = SState.HANDSHAKE;
                            }
                            break;

                        case HANDSHAKE:
                            ps.println("ACK_HANDSHAKE"); //send ACK
                            ps.flush();

                            state = SState.CHECKING;
                            now = last = System.currentTimeMillis();


                        case CHECKING:
                            now = System.currentTimeMillis();
                            //wait for client info
                            if(br.ready()) {
                                buffer = br.readLine();
                                hserver.printui("in EXCHANGING: rcv " + buffer);
                                if (buffer.equalsIgnoreCase("DUMMY_INFO")) {
                                    //Accept whatever
                                    ps.println(apconfig.SSID);   //send proxy local addr to client
                                    ps.flush();

                                    //Check policy
                                    result = checkPolicy(buffer);

                                    state = SState.SEND_RESULT;
                                }
                            }
                            else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                                if(retry < MainActivity.MAX_RETRY) {
                                    retry += 1;
                                    state = SState.HANDSHAKE; //resend
                                }
                            }
                            break;

                        case SEND_RESULT:
                            hserver.printui("in CHECKING: ");
                            //call policy to check whether to accept the request (Now fixed to accept all the time)
                            ps.println(result);
                            ps.flush();
                            //running = false;
                            now = last = System.currentTimeMillis();
                            state = SState.WAIT_ACK;
                            break;

                        case WAIT_ACK:
                            now = System.currentTimeMillis();
                            //wait for client info
                            if(br.ready()) {
                                buffer = br.readLine();
                                hserver.printui("in WAIT_ACK: rcv " + buffer);
                                if (buffer.equalsIgnoreCase("ACK_RESULT")) {
                                    running = false; //NEGOTIATION OVER
                                }
                            }
                            else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                                if(retry < MainActivity.MAX_RETRY) {
                                    retry += 1;
                                    state = SState.SEND_RESULT; //resend
                                }
                            }
                            break;

                    } //end of switch
                }//end of while
                /************************* End Negotiator Main ************************************/

                br.close();
                socket.close();
                hserver.printui("Negotiator: end of negotiator, close socket.");

            } catch (IOException e) {
                hserver.printui("Negotiator: readline error");
                e.printStackTrace();
            }

        }
    }

    private String checkPolicy(String msg){
        return "ACCEPT";
    }

    public void cancel(){
        hserver.printui("Negotiator: canceled.");
        running = false;
    }
}
