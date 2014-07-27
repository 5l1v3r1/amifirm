/**
 * Copyright (C) 2013, 2014 Iwan Timmer
 * Copyright (C) 2014 mielemann
 *
 * This file is part of AmiFirm.
 *
 * AmiFirm is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * AmiFirm is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AmiFirm.  If not, see <http://www.gnu.org/licenses/>.
 */

package nl.itimmer.amifirm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Application to download and extract Amino / Aminet firmware (MCastFSv2)
 * @author Iwan Timmer
 */
public class AmiFirm {	
	private final Map<Short, List<Packet>> filePackets;
	private final Map<Short, ByteBuffer> fileBuffer;
	private	final Map<Short, String> fileNames;
	private final Map<Short, String> directoryNames;
	
	/**
	 * Create a new instance of AmiFirm
	 */
	public AmiFirm() {
		this.filePackets = new TreeMap<>();
		this.fileBuffer = new TreeMap<>();
		this.fileNames = new TreeMap<>();
		this.directoryNames = new TreeMap<>();
	}
        
	/**
	 * Parse local firmware file
	 * @throws IOException 
	 */
	private void parseFile(File file) throws IOException, SocketTimeoutException {
		System.out.println("Parsing firmware.");

		// determine the filesize
		long size = file.length();
		ByteBuffer buffer;
		
		// keep the user informed
		System.out.println(String.format("File '%s' size: %d bytes", file.getName(), size));
		
		// try to read the file from 0..length with no overhead
		try (FileInputStream in = new FileInputStream(file)) {			
			ByteArrayOutputStream ba = new ByteArrayOutputStream();
			byte[] b = new byte[4096]; 
			while( size > 0 ) {
				in.read(b);
				ba.write(b);
				size -= 4096;
				if(size < 0)
					size = 0;
				else if(size < 4096)					
					b = new byte[(int)size];
				else
					b = new byte[4096];
			}
				
			// wrap the ba information into a buffer
			buffer = ByteBuffer.wrap(ba.toByteArray());
			
			// stop buffers
			ba.close();			
			in.close();
		}
		
		// Skip the header
		// Example header
		// 1 2  3 4  5 6  7 8  9 10 1112 1314 1516 1718 1920 2122 2324 2526 2728 2930 3132 3334
		// 0E00 4D43 6173 7446 5332 0000 4519 0400 051E 0110 0035 0000 0005 0025 009E 0000 0000 
		buffer.position(buffer.position()+28);
		buffer.getShort(); // number of file headers
		buffer.position(buffer.position()+4);
		
		// retrieve all information
		while(buffer.remaining() > 0) {
			short type = buffer.get();
			short fileId;
			byte fileType;
			short parentId;
			int fileSize;
			
			switch(type) {
				// file type
				case 0x00:
					// Examples
					// 1 2  3 4  5 6  7 8  9 10 1112 1314 1516 1718 1920 2122 2324 2526 2728 2930 3132 3334
					// 0000 0000 0000 41FF 0000 0000 0000 1000 12CE 97F0 0000
					// 000F 0000 0000 81A4 0000 0000 0000 8A37 12CE 97F0 000E 436F 7572 4249 2E74 7466 2E67 7A00
					// 002A 0000 0000 41ED 0000 0000 0000 1000 4FE8 2F18 0004 6269 6E00 											====bin
					// 002B 002A 0000 81ED 0000 0000 0000 65A9 4FE8 2F18 0016 6368 6563 6B5F 7375 7070 6F72 7465 645F 6877 2E73 6800 	check_supported_hw.sh
					// 0046 0000 0000 41FD 0000 0000 0000 1000 4FE8 2F21 0004 6574 6300 											====etc
					// 0047 0046 0000 81B4 0000 0000 0000 1B28 4FE8 2F22 0010 6C69 7374 6669 6C65 2E73 6967 6E65 6400 					listfile.signed

					// read the file information
					buffer.position(buffer.position()-1); // easiest way to make a short, lazy I know. But needed if we get fileId's >0xFF
					fileId = buffer.getShort();                        // fileid 0025					
					parentId = buffer.getShort();                    // parentid 004A
					buffer.getShort();                                   // Skip 0000
					fileType = buffer.get();                         // fileType 81
					buffer.get();                                        // Skip A4
					buffer.getShort();                                   // Skip 0000
					buffer.getInt();                                     // Skip 0000 000D // size?
					buffer.getShort();                                   // Skip F5CA // checksum?
					buffer.getInt();                                     // Skip 12CE 97F0
					short fileNameLength = buffer.getShort();  // fileNameLength 000E
					
					// Stop if we don't have a filename to read
					if(fileNameLength <= 0)						
						break;
					
					// Read the name, move the read poition
					String name = new String(buffer.array(), buffer.position(), fileNameLength);
					buffer.position(buffer.position()+fileNameLength);
									
					// Stop if we already have saved this entry
					if (directoryNames.containsKey(fileId) || fileNames.containsKey(fileId))
						break;
											
					// Remove the trailing 0x00 of a filename
					if (name.charAt(fileNameLength-1)==0)
						name = name.substring(0, fileNameLength-1);
				
					// When we have a parentId we want to include this name
					if(parentId > 0 && directoryNames.containsKey(parentId))
						name = String.format("%s/%s", directoryNames.get(parentId), name);					

					switch(String.format("0x%02X",  fileType)) {
						case "0x41":															
							// System.out.println(String.format("dirId: 0x%02X, fileType: 0x%04X, parentId: 0x%02X", fileId, fileType, parentId) +": '"+ name + "'");
							directoryNames.put(fileId, name);
							break;
						case "0x81":
							// System.out.println(String.format("fileId: 0x%02X, fileType: 0x%04X, parentId: 0x%02X", fileId, fileType, parentId) +": '"+ name + "'");
							fileNames.put(fileId, name);
							break;	
					}
					
					// Done with this file (0x00)
				  	break;
				  	
			  	case 0x04:
			  		// file contents (sometimes) 19 
			  		// 0400 051E 0110 0035 0001 0005 0026 009E 0000 0000
			  		// 0400 0536 0312 0035 0000 0006 0001 0000 1CA7 0000
			  		buffer.get();                          // skip   00
			  		short dataLength = buffer.getShort();  // length 0536
			  		short dataType = buffer.getShort();    // type   0312
			  		
			  		switch(String.format("0x%04X",  dataType)) {
			  		  	case "0x0110":
			  		  		// Unknown, no idea what this is ... so we skip
			  		  		// 0400 051E 0110 0035 0001 0005 0026 009E 0000 0000
			  		  		// 0400 0504 0110 0035 0002 0005 0024 009E 0000 0000
			  		  		// 0400 050C 0110 0035 0003 0005 0024 009E 0000 0000
			  		  		buffer.position(buffer.position() + 14);
			  		  		break;
			  		  	case "0x0312":
			  		  		// File data! split into multiple parts, normally parts are presented in sequence
			  		  		//160C  +53a // 0400 0536 0312 0035 0000 0006 0001 0000 1CA7 0000 0000 1F8B
			  		  		//1B46  +53a // 0400 0536 0312 0035 0001 0006 0001 0000 1CA7 0000 0524 522E
			  		  		//              type size data ?    part ?    id   filesize  offset    data ->
			  		  		buffer.getShort();                  // Skip     0035
			  		  		short part = buffer.getShort();     // Part     0001
			  		  	    buffer.getShort();                  // Skip     0006
			  		  	    fileId = buffer.getShort();         // fileId   0001
			  		  	    fileSize = buffer.getInt();         // fileSize 0000 1CA7
			  		  	    buffer.getInt();                    // offset   0000 0524
			  		  	    
		  		  	    	if(!fileBuffer.containsKey(fileId)) {
		  		  	    		ByteBuffer bb = ByteBuffer.allocate(fileSize);
		  		  	    		fileBuffer.put(fileId, bb);
		  		  	    	}

		  		  	    	// System.out.println("fileId: "+ String.format("0x%02X", fileId) +", part: "+  part +", fileSize: "+ fileSize +", dataLength: "+ (dataLength-16));
		  		  	    	for(int n=0; n<(dataLength-18); n++) {
		  		  	    		if(buffer.remaining() > 0)
		  		  	    			fileBuffer.get(fileId).put(buffer.get());
		  		  	    	}
				  			break;

					  	default:
					  		throw new IOException("Unsupported data header type: "+ dataType);		  		
			  		}
			  		break;
			  		
			  	default:
			  		// unknown data, maybe we misunderstood the information and did not parse it correctly
			  		System.out.println("Unknown header, printing 16 bytes before and 16 bytes after unknown header ID");
			  		buffer.position(buffer.position()-16);
			  		for(int n=0; n<16; n+=2)
			  			System.out.print(String.format("%02X %02X ", buffer.get(), buffer.get()));
			  		System.out.println("*");
			  		for(int n=0; n<16; n+=2)
			  			System.out.print(String.format("%02X %02X ", buffer.get(), buffer.get()));
			  		buffer.position(buffer.position()-16);
			  		System.out.println();
			  				  		
			  		// Show some handy information to locate it in a Hex editor
			  		System.out.println(String.format("Position 0x%08X, Remaining: 0x%08X", buffer.position(), buffer.remaining()));
			  		throw new IOException("Unsupported header type: "+ type);			  		
			}
		}

		// first version did this too
		System.out.println();
	}
	
	/**
	 * Download firmware through a multicast subscription
	 * @param address multicast address to download from
	 * @param port portnumber to download from
	 * @param export file to read and save downloaded packets to and from (can be null)
	 * @throws IOException 
	 */
	private void download(InetAddress address, int port, File export) throws IOException, SocketTimeoutException {
		System.out.println("Press key to stop downloading");
		System.out.print("Downloading firmware...");
		try (MulticastSocket socket = new MulticastSocket(port)) {
			socket.setSoTimeout(5000);
			socket.joinGroup(address);
			
			Set<String> keys = new HashSet<>();
			boolean running = true;
			byte[] buffer = new byte[1500];
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			
			FileOutputStream out = null;
			if (export!=null)
				out = new FileOutputStream(export);
			
			int n = 0;
			long last = 0;
			while (running) {
				String key = new String(buffer, 0, 10);
				socket.receive(packet);
				if (!keys.contains(key)) {
					if (out!=null) {
						ByteBuffer size = ByteBuffer.allocate(4).putInt(packet.getLength());
						out.write(size.array(), 0, 4);
						out.write(buffer, 0, packet.getLength());
					}
					
					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, packet.getLength());
					Packet firmwarePacket = null;
					short id = 0;
					switch (buffer[0]) {
						case FilePacket.TYPE:
							firmwarePacket = new FilePacket(byteBuffer);
							id = ((FilePacket) firmwarePacket).getFileId();
							break;
						case HeaderPacket.TYPE:
							firmwarePacket = new HeaderPacket(byteBuffer);
							break;
						default:
							throw new IOException("Not supported firmware");
					}
					keys.add(key);
					
					if (!filePackets.containsKey(id)) {
						System.out.print('+');
						filePackets.put(id, new ArrayList<Packet>());
					}

					filePackets.get(id).add(firmwarePacket);
					
					if (firmwarePacket instanceof HeaderPacket && fileNames.isEmpty()) {
						if (firmwarePacket.getTotalPackets() == filePackets.get((short) 0).size()) {
							System.out.println();
							parseHeaders();
							System.out.print("Reading firmware...");
						}
					}
					
					buffer = new byte[buffer.length];
					if ((n++)%50 == 0 || last+1000<System.currentTimeMillis()) {
						System.out.print('.');
						last = System.currentTimeMillis();
					}
					
					if (filePackets.size() == (fileNames.size()+1)) {
						running = false;
						for (Short fileId:filePackets.keySet()) {
							List<Packet> packets = filePackets.get(fileId);
							if (packets.get(0).getTotalPackets() != packets.size()) {
								running = true;
								break;
							}
						}
					}
				}
				packet.setData(buffer);
				
				if (System.in.available()>0)
					running = false;
			}
		}
		System.out.println();
		filePackets.remove((short) 0);
	}
	
	/**
	 * Parse the headers from an active multicast download stream
	 * @see download()
	 * @throws IOException 
	 */
	private void parseHeaders() {
		System.out.println("Parse headers...");
		List<Packet> packets = filePackets.get((short) 0);
		Collections.sort(packets);
		for (Packet packet:packets) {
			ByteBuffer buffer = packet.getData();
			buffer.position(Packet.HEADER_SIZE + (packet.getPacketId()==0?20:-2));
			while (buffer.remaining()>0) {
				short fileId = buffer.getShort();                 // fileid  0000
				buffer.position(buffer.position()+6);             // Skip    0001 0000 81A4
				buffer.getInt();                            // Read unknown  0000 0000
				buffer.getInt();                            // Read filesize 0000 1CA7
				buffer.position(buffer.position()+4);             // Skip    12CE 97F0
				short fileNameLength = buffer.getShort(); // Filename length 0000
				String name = new String(buffer.array(), buffer.position(), fileNameLength);
				if (name.charAt(fileNameLength-1)==0)
					name = name.substring(0, fileNameLength-1);
				
				buffer.position(buffer.position()+fileNameLength);
				fileNames.put(fileId, name);
			}
		}
	}
	
	
	/**
	 * Extract firmware
	 * @param dir Directory to extract files to
	 * @throws IOException 
	 */
	private void extract(File dir) throws FileNotFoundException, IOException {
		System.out.println("Creating directories...");
		
		for (String name : directoryNames.values()) {
			File d = new File(dir, name);
			
			if(!d.exists())
				d.mkdirs();
		}
		
		// for each file found
		for (Short fileId : fileNames.keySet()) {
			String fileName = fileNames.get(fileId);
			
		}	
		
		System.out.println("Saving files from packets...");
		
		for (Short fileId : fileNames.keySet()) {
			String fileName = fileNames.get(fileId);
			if (filePackets.containsKey(fileId) && filePackets.get(fileId).size() == filePackets.get(fileId).get(0).getTotalPackets()) {
				System.out.println("Extracting " + fileName);
				List<Packet> selectedFilePacket = filePackets.get(fileId);
				Collections.sort(selectedFilePacket);

				try (FileOutputStream out = new FileOutputStream(new File(dir, fileNames.get(fileId)))) {
					for (Packet packet:selectedFilePacket) {
						ByteBuffer buffer = packet.getData();
						out.write(buffer.array(), Packet.HEADER_SIZE, buffer.limit()-Packet.HEADER_SIZE);
					}
				}
			} if (fileBuffer.containsKey(fileId)) {
				System.out.println("Extracting " + fileName);
				ByteBuffer buffer = fileBuffer.get(fileId);
				buffer.rewind();
				
				// open the output (append is false), write and close
				try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
					out.write(buffer.array(), 0, buffer.remaining());
				}
			} else {
				if (filePackets.containsKey(fileId))
					System.err.println(fileName + " not found");
				else
					System.err.println(fileName + " incomplete");
			}
		}
	}
	
	public static void main(String args[]) {
		System.out.println("AmiFirm 0.2.0");
		System.out.println("Copyright (c) 2013-2014 Iwan Timmer");
		System.out.println("Copyright (c) 2014 mielleman");
		System.out.println("Distributed under the GNU GPL v3. For full terms see the LICENSE file.\n");

		int port = 0;
		InetAddress address = null;
		File file = null;
		File dir = null;
		File save = null;
		boolean usage = false;
		
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-m":
					i++;
					if (i == args.length) {
						System.err.println("Multicast address is missing");
						usage = true;
					}
					
					String multicast[] = args[i].split(":");
					if (multicast.length != 2) {
						System.err.println("Address is not a multicast address");
						usage = true;
					}
					
					try {
						address = InetAddress.getByName(multicast[0]);
					} catch (UnknownHostException e) {
						address = null;
					}
					
					if (address != null && !address.isMulticastAddress()) {
						System.err.println("Address is not a multicast address");
						usage = true;
					}
					
					try {
						port = Integer.parseInt(multicast[1]);
					} catch (NumberFormatException e) {
						port = -1;
					}
					
					if (port<0||port>65535) {
						System.err.println("Port is not a valid portnumber");
						usage = true;
					}
					break;
				case "-s":
					i++;
					if (i == args.length) {
						System.err.println("File name is missing");
						usage = true;
					}
					save = new File(args[i]);
					break;
				case "-f":
					i++;
					if (i == args.length) {
						System.err.println("File name is missing");
						usage = true;
					}
					file = new File(args[i]);
					if (!file.exists()) {
						System.err.println("File doesn't exist");
						usage = true;
					}
					break;
				case "-d":
					i++;
					if (i == args.length) {
						System.err.println("Directory name is missing");
						usage = true;
					}
					dir = new File(args[i]);
					dir.mkdir();
					if (!dir.isDirectory()) {
						System.err.println("Directory is not valid");
						usage = true;
					}
					break;
			}
		}
		
		if (file != null && dir == null) {
			System.err.println("Need directory to extract to");
			usage = true;
		}
		
		if (address != null) {
			if (file != null) {
				System.err.println("You need to specify either a firmware file or a multicast address");
				usage = true;
			}
		}
		
		if (usage) {
			System.out.println("Usage: java -jar amifirm.jar [options]");
			System.out.println("\t-m [multicast address:port]\t\taddress to download firmware from");
			System.out.println("\t-f [file]\t\tname of local MCastFSv2 file");
			System.out.println("\t-d [path]\t\tpath to extract firmware files to");
			System.out.println("\t-s [filename]\t\tfile to cache firmware packets (multicast only)");
			System.exit(-1);
		}

		try {
			AmiFirm firm = new AmiFirm();
			
			if (address != null)
				firm.download(address, port, save);
			if (file != null)
				firm.parseFile(file);
			
			if (dir != null)
				firm.extract(dir);
		} catch (SocketTimeoutException e) {
			System.err.println("Couldn't receive firmware data");
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}
	
}
