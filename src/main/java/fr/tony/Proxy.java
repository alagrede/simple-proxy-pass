package fr.tony;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The Proxy creates a Server Socket which will wait for connections on the specified port.
 * Once a connection arrives and a socket is accepted, the Proxy creates a RequestHandler object
 * on a new thread and passes the socket to it to be handled.
 * This allows the Proxy to continue accept further connections while others are being handled.
 * 
 * Upon closing the proxy server, the HashMaps which hold cached items and blocked sites are serialized and
 * written to a file and are loaded back in when the proxy is started once more, meaning that cached and blocked
 * sites are maintained.
 *
 * @author alagrede
 *
 */
public class Proxy {

	static Logger logger = LoggerFactory.getLogger(Proxy.class);

	public static void main(String[] args) {
		Proxy myProxy = new Proxy(8085);
		myProxy.listen();	
	}


	private ServerSocket serverSocket;

	/**
	 * Semaphore for Proxy and Consolee Management System.
	 */
	private volatile boolean running = true;


	/**
	 * ArrayList of threads that are currently running and servicing requests.
	 * This list is required in order to join all threads on closing of server
	 */
	static ArrayList<Thread> servicingThreads;



	/**
	 * Create the Proxy Server
	 * @param port Port number to run proxy server from.
	 */
	public Proxy(int port) {

		// Create array list to hold servicing threads
		servicingThreads = new ArrayList<>();

		try {
			// Create the Server Socket for the Proxy 
			serverSocket = new ServerSocket(port);

			// Set the timeout
			//serverSocket.setSoTimeout(100000);	// debug
			logger.info("Waiting for client on port " + serverSocket.getLocalPort() + "..");
			running = true;
		} 

		// Catch exceptions associated with opening socket
		catch (SocketException se) {
			logger.error("Socket Exception when connecting to client", se);
		}
		catch (SocketTimeoutException ste) {
			logger.error("Timeout occured while connecting to client", ste);
		} 
		catch (IOException io) {
			logger.error("IO exception when connecting to client", io);
		}
	}


	/**
	 * Listens to port and accepts new socket connections. 
	 * Creates a new thread to handle the request and passes it the socket connection and continues listening.
	 */
	public void listen(){

		while(running){
			try {
				// serverSocket.accpet() Blocks until a connection is made
				Socket socket = serverSocket.accept();

				// Create new Thread and pass it Runnable RequestHandler
				Thread thread = new Thread(new RequestHandler(socket));

				// Key a reference to each thread so they can be joined later if necessary
				servicingThreads.add(thread);

				thread.start();	
			} catch (SocketException e) {
				// Socket exception is triggered by management system to shut down the proxy 
				logger.info("Server closed");
			} catch (IOException e) {
				logger.error("Server error", e);
			}
		}
	}


	/**
	 * Joins all of the RequestHandler threads currently servicing requests.
	 */
	public void closeServer(){
		logger.info("Closing Server..");
		running = false;

		try{
			// Close all servicing threads
			for(Thread thread : servicingThreads){
				if(thread.isAlive()){
					logger.info("Waiting on "+  thread.getId()+" to close..");
					thread.join();
					logger.info(" closed");
				}
			}
		} catch (InterruptedException e) {
			logger.error("Error during closing server", e);
		}

		// Close Server Socket
		try{
			logger.info("Terminating Connection");
			serverSocket.close();
		} catch (Exception e) {
			logger.error("Exception closing proxy's server socket", e);
		}

	}

}