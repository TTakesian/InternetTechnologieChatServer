package nl.saxion.internettech;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static nl.saxion.internettech.ServerState.*;

public class Server {
    //A static array to hold all connected clients to the server
    private ServerSocket serverSocket;
    private Set<ClientThread> threads;
    private ServerConfiguration conf;
    private ArrayList<ClientThreadGroup> clientGroups;
    //An arraylist to hold the groups that are going to be created
    public Server(ServerConfiguration conf) {
        this.conf = conf;
    }

    /**
     * Runs the server. The server listens for incoming client connections
     * by opening a socket on a specific port.
     */
    public void run() {
        clientGroups = new ArrayList<>();
        // Create a socket to wait for clients.
        try {
            serverSocket = new ServerSocket(conf.SERVER_PORT);
            threads = new HashSet<>();

            while (true) {
                // Wait for an incoming client-connection request (blocking).
                Socket socket = serverSocket.accept();

                // When a new connection has been established, start a new thread.
                ClientThread ct = new ClientThread(socket);
                threads.add(ct);
                new Thread(ct).start();
                System.out.println("Num clients: " + threads.size());

                // Simulate lost connections if configured.
                if(conf.doSimulateConnectionLost()){
                    DropClientThread dct = new DropClientThread(ct);
                    new Thread(dct).start();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This thread sleeps for somewhere between 10 tot 20 seconds and then drops the
     * client thread. This is done to simulate a lost in connection.
     */
    private class DropClientThread implements Runnable {
        ClientThread ct;

        DropClientThread(ClientThread ct){
            this.ct = ct;
        }

        public void run() {
            try {
                // Drop a client thread between 10 to 20 seconds.
                int sleep = (10 + new Random().nextInt(10)) * 1000;
                Thread.sleep(sleep);
                ct.kill();
                threads.remove(ct);
                System.out.println("Num clients: " + threads.size());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This inner class is used to handle all communication between the server and a
     * specific client.
     */
    private class ClientThread implements Runnable {

        private DataInputStream is;
        private OutputStream os;
        private Socket socket;
        private ServerState state;
        private String username;

        public ClientThread(Socket socket) {
            this.state = INIT;
            this.socket = socket;
        }

        public String getUsername() {
            return username;
        }

        public OutputStream getOutputStream() {
            return os;
        }

        public void run() {
            try {
                // Create input and output streams for the socket.
                os = socket.getOutputStream();
                is = new DataInputStream(socket.getInputStream());
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                // According to the protocol we should send HELO <welcome message>
                state = CONNECTING;
                String welcomeMessage = "HELO " + conf.WELCOME_MESSAGE;
                writeToClient(welcomeMessage);

                while (!state.equals(FINISHED)) {
                    // Wait for message from the client.
                    String line = reader.readLine();
                    if (line != null) {
                        // Log incoming message for debug purposes.
                        boolean isIncomingMessage = true;
                        logMessage(isIncomingMessage, line);

                        // Parse incoming message.
                        Message message = new Message(line);

                        // Process message.
                        switch (message.getMessageType()) {
                            case HELO:
                                // Check username format.
                                boolean isValidUsername = message.getPayload().matches("[a-zA-Z0-9_]{3,14}");
                                if(!isValidUsername) {
                                    state = FINISHED;
                                    writeToClient("-ERR username has an invalid format (only characters, numbers and underscores are allowed)");
                                } else {
                                    // Check if user already exists.
                                    boolean userExists = false;
                                    for (ClientThread ct : threads) {
                                        if (ct != this && message.getPayload().equals(ct.getUsername())) {
                                            userExists = true;
                                            break;
                                        }
                                    }
                                    if (userExists) {
                                        writeToClient("-ERR user already logged in");
                                    } else {
                                        state = CONNECTED;
                                        this.username = message.getPayload();
                                        writeToClient("+OK " + getUsername());
                                    }
                                }
                                break;
                            case BCST:
                                // Broadcast to other clients.
                                for (ClientThread ct : threads) {
                                    if (ct != this) {
                                        ct.writeToClient("BCST [" + getUsername() + "] " + message.getPayload());
                                    }
                                }
                                writeToClient("+OK");
                                break;
                            case PRIVATE:
                                //Send a private message to a selected client
                                //Split the message payload to get the username of that client and the message
                                String[] messageSplit = message.getPayload().split("-");
                                String to = messageSplit[0];
                                String messageToSend = this.username +"has sent you a message: "+messageSplit[1];
                                boolean sent = false;
                                //Loop through all threads to find the selected user by his username, if found then send message to
                                for (ClientThread ct : threads){
                                    if(ct.getUsername().equals(to)){
                                        ct.writeToClient(messageToSend);
                                        sent = true;
                                        break;
                                    }
                                }
                                if (sent) {
                                    writeToClient("+OK Private message has been sent to client: " + to);
                                } else {
                                    writeToClient("-ERR Private message sending has failed.");
                                }

                                break;
                            case ALLCLIENTS:
                                //This will return all of the sockets(clients) that are connected to the server
                                String messageToClient = "";
                                for (ClientThread ct: threads){
                                    messageToClient+= "Username: "+ct.username+" is connected to the server. Socket: "+ct.socket.toString();
                                }
                                writeToClient("+OK "+messageToClient);
                                break;
                            case NEWGROUP:
                                //Get the group name from the message payload
                                String groupName = message.getPayload();
                                //Create a new group using the given group name and the owner that is creating the group
                                ClientThreadGroup clientGroup = new ClientThreadGroup(groupName,this);
                                //Add the new group to the existing groups
                                clientGroups.add(clientGroup);
                                writeToClient("+OK New group with the name "+groupName+" has been added.");
                                break;
                            case ALLGROUPS:
                                messageToClient = "";
                                for (ClientThreadGroup ctg: clientGroups){
                                    messageToClient += "Group: "+ctg.name+", has "+ctg.getGroupSize()+" participants. \n";
                                }
                                writeToClient("+OK"+messageToClient);
                                break;
                            case JOINGROUP:
                                messageToClient = "";
                                for (ClientThreadGroup ctg: clientGroups){
                                    if(ctg.name.equalsIgnoreCase(message.getPayload())){
                                        ctg.setClientThreads(this);
                                        messageToClient = "+OK You have been added to the group: "+ctg.name;
                                        writeToClient(messageToClient);
                                        break;
                                    }
                                }
                                writeToClient("-ERR Group name not found");
                                break;
                            case QUITGROUP:
                                //First check if the given group name exists
                                for (ClientThreadGroup ctg: clientGroups){
                                    //If so then proceed
                                    if(ctg.getName().equals(message.getPayload())){
                                        //Retrieve the selected group
                                        ArrayList<ClientThread> clientThreadArrayList = ctg.getClientThreads();
                                        //Loop through the group to remove the client
                                        for (ClientThread ct: clientThreadArrayList){
                                            //If the client exists in the group
                                            if(ct.getUsername().equals(getUsername())){
                                                //Delete the selected client
                                                ctg.deleteClientThread(ct);
                                                writeToClient("+OK You are no longer a participant in this group: "+ctg.getName());
                                                break;
                                            }
                                        }
                                    }else {
                                        writeToClient("-ERR Group name does not exist.");
                                    }
                                }
                                break;
                            case KICKUSER:
                                messageToClient = "";
                                String[] messageSplitKick = message.getPayload().split("-");
                                String groupKick = messageSplitKick[0];
                                String userKick = messageSplitKick[1];
                                boolean kicked = false;
                                //Loop through all of the groups
                                for (ClientThreadGroup ctg: clientGroups){
                                    //If the group exists and I am the owner of this group then proceed with kicking
                                    if(ctg.name.equalsIgnoreCase(groupKick) && ctg.owner.username.equals(this.username)){
                                        //Loop through the list of client threads in this group
                                        for (ClientThread clientThread: ctg.clientThreads){
                                            //If the required user is found in the list
                                            if(userKick.equalsIgnoreCase(clientThread.username)){
                                                //Kick the user from the group
                                                ctg.deleteClientThread(clientThread);
                                                messageToClient = "+OK User "+message.getPayload()+" has been kicked from the group";
                                                kicked = true;
                                                break;
                                            }
                                        }
                                        break;
                                    }
                                }

                                if(kicked){
                                    this.writeToClient(messageToClient);
                                }else {
                                    this.writeToClient("-ERR Failed to kick the selected user");
                                }
                                break;
                            case QUIT:
                                // Close connection
                                state = FINISHED;
                                writeToClient("+OK Goodbye");
                                break;
                            case UNKOWN:
                                // Unkown command has been sent
                                writeToClient("-ERR Unkown command");
                                break;
                        }
                    }
                }
                // Remove from the list of client threads and close the socket.
                threads.remove(this);
                socket.close();
            } catch (IOException e) {
                System.out.println("Server Exception: " + e.getMessage());
            }
        }

        /**
         * An external process can stop the client using this methode.
         */
        public void kill() {
            try {
                // Log connection drop and close the outputstream.
                System.out.println("[DROP CONNECTION] " + getUsername());
                threads.remove(this);
                socket.close();
            } catch(Exception ex) {
                System.out.println("Exception when closing outputstream: " + ex.getMessage());
            }
            state = FINISHED;
        }

        /**
         * Write a message to this client thread.
         * @param message   The message to be sent to the (connected) client.
         */
        private void writeToClient(String message) {
            boolean shouldDropPacket = false;
            boolean shouldCorruptPacket = false;

            // Check if we need to behave badly by dropping some messages.
            if (conf.doSimulateDroppedPackets()) {
                // Randomly select if we are going to drop this message or not.
                int random = new Random().nextInt(6);
                if (random == 0) {
                    // Drop message.
                    shouldDropPacket = true;
                    System.out.println("[DROPPED] " + message);
                }
            }

            // Check if we need to behave badly by corrupting some messages.
            if (conf.doSimulateCorruptedPackets()) {
                // Randomly select if we are going to corrupt this message or not.
                int random = new Random().nextInt(4);
                if (random == 0) {
                    // Corrupt message.
                    shouldCorruptPacket = true;
                }
            }

            // Do the actual message sending here.
            if (!shouldDropPacket) {
                if (shouldCorruptPacket){
                    message = corrupt(message);
                    System.out.println("[CORRUPT] " + message);
                }
                PrintWriter writer = new PrintWriter(os);
                writer.println(message);
                writer.flush();

                // Echo the message to the server console for debugging purposes.
                boolean isIncomingMessage = false;
                logMessage(isIncomingMessage, message);
            }
        }

        /**
         * This methods implements a (naive) simulation of a corrupt message by replacing
         * some charaters at random indexes with the charater X.
         * @param message   The message to be corrupted.
         * @return  Returns the message with some charaters replaced with X's.
         */
        private String corrupt(String message) {
            Random random = new Random();
            int x = random.nextInt(4);
            char[] messageChars =  message.toCharArray();

            while (x < messageChars.length) {
                messageChars[x] = 'X';
                x = x + random.nextInt(10);
            }

            return new String(messageChars);
        }

        /**
         * Util method to print (debug) information about the server's incoming and outgoing messages.
         * @param isIncoming    Indicates whether the message was an incoming message. If false then
         *                      an outgoing message is assumed.
         * @param message       The message received or sent.
         */
        private void logMessage(boolean isIncoming, String message) {
            String logMessage;
            String colorCode = conf.CLI_COLOR_OUTGOING;
            String directionString = ">> ";  // Outgoing message.
            if (isIncoming) {
                colorCode = conf.CLI_COLOR_INCOMING;
                directionString = "<< ";     // Incoming message.
            }

            // Add username to log if present.
            // Note when setting up the connection the user is not known.
            if (getUsername() == null) {
                logMessage = directionString + message;
            } else {
                logMessage = directionString + "[" + getUsername() + "] " + message;
            }

            // Log debug messages with or without colors.
            if(conf.isShowColors()){
                System.out.println(colorCode + logMessage + conf.RESET_CLI_COLORS);
            } else {
                System.out.println(logMessage);
            }
        }
    }

    private class ClientThreadGroup{
        private String name;
        private ClientThread owner;
        private ArrayList<ClientThread> clientThreads = new ArrayList<>();

        public ClientThreadGroup(String name, ClientThread owner){
            this.name = name;
            this.owner = owner;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public ClientThread getOwner() {
            return owner;
        }

        public void setOwner(ClientThread owner) {
            this.owner = owner;
        }

        public ArrayList<ClientThread> getClientThreads() {
            return this.clientThreads;
        }

        public void setClientThreads(ClientThread newClientThread) {
            this.clientThreads.add(newClientThread);
        }

        public void deleteClientThread(ClientThread clientThread) { this.clientThreads.remove(clientThread); }

        public int getGroupSize(){
            return this.clientThreads.size();
        }
    }
}
