/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.android_scripting;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A simple server.
 * 
 * @author Damon Kohler (damonkohler@gmail.com)
 */
public abstract class SimpleServer {

  private ServerSocket mServer;
  private Thread mServerThread;
  private final CopyOnWriteArrayList<ConnectionThread> mNetworkThreads;
  private volatile boolean mStopServer = false;

  protected abstract void handleConnection(Socket socket) throws Exception;

  /**
   * Construct a {@link SimpleServer} connected to the provided {@link RpcReceiver}s.
   * 
   * @param receivers
   *          the {@link RpcReceiver}s to register with the server
   */
  public SimpleServer() {
    mNetworkThreads = new CopyOnWriteArrayList<ConnectionThread>();
  }

  private final class ConnectionThread extends Thread {
    private final Socket mmSocket;

    private ConnectionThread(Socket socket) {
      mmSocket = socket;
    }

    @Override
    public void run() {
      Log.v("Server thread " + getId() + " started.");
      try {
        handleConnection(mmSocket);
      } catch (Exception e) {
        if (!mStopServer) {
          Log.e("Server error.", e);
        }
      } finally {
        close();
        mNetworkThreads.remove(this);
        Log.v("Server thread " + getId() + " died.");
      }
    }

    private void close() {
      if (mmSocket != null) {
        try {
          mmSocket.close();
        } catch (IOException e) {
          Log.e(e.getMessage(), e);
        }
      }
    }
  }

  private InetAddress getPublicInetAddress() throws UnknownHostException, SocketException {
    Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
    for (NetworkInterface netint : Collections.list(nets)) {
      Enumeration<InetAddress> addresses = netint.getInetAddresses();
      for (InetAddress address : Collections.list(addresses)) {
        if (!address.getHostAddress().equals("127.0.0.1")) {
          return address;
        }
      }
    }
    return InetAddress.getLocalHost();
  }

  /**
   * Starts the RPC server bound to the localhost address.
   * 
   * @return the port that was allocated by the OS
   */
  public InetSocketAddress startLocal() {
    InetAddress address;
    try {
      address = InetAddress.getLocalHost();
      mServer = new ServerSocket(0 /* port */, 5 /* backlog */, address);
    } catch (Exception e) {
      Log.e("Failed to start server.", e);
      return null;
    }
    int port = start(address);
    return new InetSocketAddress(address, port);
  }

  /**
   * data Starts the RPC server bound to the public facing address.
   * 
   * @return the port that was allocated by the OS
   */
  public InetSocketAddress startPublic() {
    InetAddress address;
    try {
      address = getPublicInetAddress();
      mServer = new ServerSocket(0 /* port */, 5 /* backlog */, address);
    } catch (Exception e) {
      Log.e("Failed to start server.", e);
      return null;
    }
    int port = start(address);
    return new InetSocketAddress(address, port);
  }

  private int start(InetAddress address) {
    mServerThread = new Thread() {
      @Override
      public void run() {
        while (!mStopServer) {
          try {
            Socket sock = mServer.accept();
            if (!mStopServer) {
              startConnectionThread(sock);
            } else {
              sock.close();
            }
          } catch (IOException e) {
            if (!mStopServer) {
              Log.e("Failed to accept connection.", e);
            }
          }
        }
      }
    };
    mServerThread.start();
    Log.v("Bound to " + address.getHostAddress() + ":" + mServer.getLocalPort());
    return mServer.getLocalPort();
  }

  private void startConnectionThread(final Socket sock) {
    ConnectionThread networkThread = new ConnectionThread(sock);
    mNetworkThreads.add(networkThread);
    networkThread.start();
  }

  public void shutdown() {
    // Stop listening on the server socket to ensure that
    // beyond this point there are no incoming requests.
    mStopServer = true;
    try {
      mServer.close();
    } catch (IOException e) {
      Log.e("Failed to close server socket.", e);
    }
    // Since the server is not running, the mNetworkThreads set can only
    // shrink from this point onward. We can just stop all of the running helper
    // threads. In the worst case, one of the running threads will already have
    // shut down. Since this is a CopyOnWriteList, we don't have to worry about
    // concurrency issues while iterating over the set of threads.
    for (ConnectionThread networkThread : mNetworkThreads) {
      networkThread.close();
    }
  }
}