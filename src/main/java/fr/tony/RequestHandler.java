package fr.tony;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle the request in a dedicated thread
 * 
 * @author alagrede
 *
 */
public class RequestHandler implements Runnable {

	static Logger logger = LoggerFactory.getLogger(Proxy.class);
	
	/**
	 * Socket connected to client passed by Proxy server
	 */
	Socket clientSocket;

	/**
	 * Read data client sends to proxy
	 */
	BufferedReader proxyToClientBr;

	/**
	 * Send data from proxy to client
	 */
	BufferedWriter proxyToClientBw;
	

	/**
	 * Thread that is used to transmit data read from client to server when using HTTPS
	 * Reference to this is required so it can be closed once completed.
	 */
	private Thread httpsClientToServer;

	
	/**
	 * Headers parse from clientSocket
	 */
	private Map<String, String> headers = new HashMap<>();

	/**
	 * Creates a ReuqestHandler object capable of servicing HTTP(S) GET requests
	 * @param clientSocket socket connected to the client
	 */
	public RequestHandler(Socket clientSocket){
		this.clientSocket = clientSocket;
		try{
			this.clientSocket.setSoTimeout(1000);
			proxyToClientBr = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			proxyToClientBw = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
		} 
		catch (IOException e) {
			logger.error("Unable to open socket", e);
		}
	}


	
	/**
	 * Reads and examines the requestString and calls the appropriate method based 
	 * on the request type. 
	 */
	@Override
	public void run() {

		// Get Request from client
		String requestString;
		try{
			requestString = proxyToClientBr.readLine();
			
			// Host: 127.0.0.1:8580
			// User-Agent: Java/1.8.0_191

			String output = "";
			while (output != null) {
				output = proxyToClientBr.readLine();
				if (output.contains(":")) {
					logger.debug("Header: " + output);
					int indexOf = output.indexOf(":");
					String key = output.substring(0, indexOf);
					String value = output.substring(indexOf + 1, output.length());
					headers.put(key, value);
				}
				if ("".equals(output)) { // nothing to read
					output = null;
				}
			}

		} catch (IOException e) {
			logger.error("Error reading request from client", e);
			return;
		}

		// Parse out URL

		logger.debug("Request Received " + requestString);
		// Get the Request type
		String request = requestString.substring(0,requestString.indexOf(' '));

		// remove request type and space
		String urlString = requestString.substring(requestString.indexOf(' ')+1);

		// Remove everything past next space
		urlString = urlString.substring(0, urlString.indexOf(' '));

		// Prepend http:// if necessary to create correct URL
		if(!urlString.substring(0,4).equals("http")){
			String temp = "http://";
			urlString = temp + urlString;
		}

		// Check request type HTTPS
		if(request.equals("CONNECT")){
			logger.debug("HTTPS Request for : " + urlString);
			handleHTTPSRequest(urlString);
		
		} else { // HTTP
			logger.debug("HTTP GET for : " + urlString);
			sendNonCachedToClient(urlString);
		}
	} 


	/**
	 * Sends the contents of the file specified by the urlString to the client
	 * @param urlString URL ofthe file requested
	 */
	private void sendNonCachedToClient(String urlString){

		BufferedReader proxyToServerBR = null;
		
		try{
				// Create the URL
				URL remoteURL = new URL(urlString);
				// Create a connection to remote server
				HttpURLConnection proxyToServerCon = (HttpURLConnection)remoteURL.openConnection();
//				proxyToServerCon.setRequestProperty("Content-Type", 
//						"application/x-www-form-urlencoded");
//				proxyToServerCon.setRequestProperty("Content-Type", "application/json");
//				proxyToServerCon.setRequestProperty("Content-Language", "en-US");

				// Follow headers
				for (Entry<String, String> header : this.headers.entrySet()) {
					proxyToServerCon.setRequestProperty(header.getKey(), header.getValue());
				}
				
				proxyToServerCon.setUseCaches(false);
				proxyToServerCon.setDoOutput(true);
			
				// Create Buffered Reader from remote Server
				proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerCon.getInputStream()));
				

				// Send success code to client
				String line = "HTTP/1.0 200 OK\n" +
						"Proxy-agent: ProxyServer/1.0\n" +
						"\r\n";
				proxyToClientBw.write(line);
				
				
				// Read from input stream between proxy and remote server
				while((line = proxyToServerBR.readLine()) != null){
					// Send on data to client
					proxyToClientBw.write(line);
				}
				
				// Ensure all data is sent by this point
				proxyToClientBw.flush();

		
		} catch (Exception e){
			logger.error("Error during redirecting non cached request.", e);
		
		} finally {
			try {
				// Close Down Resources
				if(proxyToServerBR != null){
					proxyToServerBR.close();
				}
				
				if(proxyToClientBw != null){
					proxyToClientBw.close();
				}
			} catch (IOException e) {
				logger.error("Error when closing proxy connection", e);
			}

		}
	}

	
	/**
	 * Handles HTTPS requests between client and remote server
	 * @param urlString desired file to be transmitted over https
	 */
	private void handleHTTPSRequest(String urlString){
		// Extract the URL and port of remote 
		String url = urlString.substring(7);
		String[] pieces = url.split(":");
		url = pieces[0];
		int port  = Integer.valueOf(pieces[1]);

		try{
			// Only first line of HTTPS request has been read at this point (CONNECT *)
			// Read (and throw away) the rest of the initial data on the stream
			for(int i=0;i<5;i++){
				proxyToClientBr.readLine();
			}

			// Get actual IP associated with this URL through DNS
			InetAddress address = InetAddress.getByName(url);
			
			// Open a socket to the remote server 
			Socket proxyToServerSocket = new Socket(address, port);
			proxyToServerSocket.setSoTimeout(5000);

			// Send Connection established to the client
			String line = "HTTP/1.0 200 Connection established\r\n" +
					"Proxy-Agent: ProxyServer/1.0\r\n" +
					"\r\n";
			proxyToClientBw.write(line);
			proxyToClientBw.flush();
			
			
			
			// Client and Remote will both start sending data to proxy at this point
			// Proxy needs to asynchronously read data from each party and send it to the other party


			//Create a Buffered Writer betwen proxy and remote
			BufferedWriter proxyToServerBW = new BufferedWriter(new OutputStreamWriter(proxyToServerSocket.getOutputStream()));

			// Create Buffered Reader from proxy and remote
			BufferedReader proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerSocket.getInputStream()));



			// Create a new thread to listen to client and transmit to server
			ClientToServerHttpsTransmit clientToServerHttps = 
					new ClientToServerHttpsTransmit(clientSocket.getInputStream(), proxyToServerSocket.getOutputStream());
			
			httpsClientToServer = new Thread(clientToServerHttps);
			httpsClientToServer.start();
			
			
			// Listen to remote server and relay to client
			try {
				byte[] buffer = new byte[4096];
				int read;
				do {
					read = proxyToServerSocket.getInputStream().read(buffer);
					if (read > 0) {
						clientSocket.getOutputStream().write(buffer, 0, read);
						if (proxyToServerSocket.getInputStream().available() < 1) {
							clientSocket.getOutputStream().flush();
						}
					}
				} 
				while (read >= 0);
			
			} catch (SocketTimeoutException e) {
				
			} catch (IOException e) {
				logger.error("Unable to write to the remote server.", e);
			}


			// Close Down Resources
			if(proxyToServerSocket != null){
				proxyToServerSocket.close();
			}

			if(proxyToServerBR != null){
				proxyToServerBR.close();
			}

			if(proxyToServerBW != null){
				proxyToServerBW.close();
			}

			if(proxyToClientBw != null){
				proxyToClientBw.close();
			}
			
			
		} catch (SocketTimeoutException e) {
			String line = "HTTP/1.0 504 Timeout Occured after 10s\n" +
					"User-Agent: ProxyServer/1.0\n" +
					"\r\n";
			try{
				proxyToClientBw.write(line);
				proxyToClientBw.flush();
			} catch (IOException ioe) {
				logger.error("Unable to make a response to the client after a server socket timeout.", ioe);
			}
		} 
		catch (Exception e){
			logger.error("Error on HTTPS : " + urlString, e);
		}
	}

	


	/**
	 * Listen to data from client and transmits it to server.
	 * This is done on a separate thread as must be done 
	 * asynchronously to reading data from server and transmitting 
	 * that data to the client. 
	 */
	class ClientToServerHttpsTransmit implements Runnable{
		
		InputStream proxyToClientIS;
		OutputStream proxyToServerOS;
		
		/**
		 * Creates Object to Listen to Client and Transmit that data to the server
		 * @param proxyToClientIS Stream that proxy uses to receive data from client
		 * @param proxyToServerOS Stream that proxy uses to transmit data to remote server
		 */
		public ClientToServerHttpsTransmit(InputStream proxyToClientIS, OutputStream proxyToServerOS) {
			this.proxyToClientIS = proxyToClientIS;
			this.proxyToServerOS = proxyToServerOS;
		}

		@Override
		public void run(){
			try {
				// Read byte by byte from client and send directly to server
				byte[] buffer = new byte[4096];
				int read;
				do {
					read = proxyToClientIS.read(buffer);
					if (read > 0) {
						proxyToServerOS.write(buffer, 0, read);
						if (proxyToClientIS.available() < 1) {
							proxyToServerOS.flush();
						}
					}
				} 
				while (read >= 0);
			}
			catch (SocketTimeoutException ste) {
				logger.error("Socket exception with client", ste);
			}
			catch (IOException e) {
				logger.error("Proxy to client HTTPS read timed out", e);
			}
		}
	}
	
}