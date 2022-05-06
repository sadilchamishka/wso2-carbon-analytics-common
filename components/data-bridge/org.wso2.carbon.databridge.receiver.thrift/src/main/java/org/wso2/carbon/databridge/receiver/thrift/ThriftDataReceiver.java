/**
 * Copyright (c) WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.databridge.receiver.thrift;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.databridge.commons.exception.TransportException;
import org.wso2.carbon.databridge.commons.thrift.service.general.ThriftEventTransmissionService;
import org.wso2.carbon.databridge.commons.thrift.service.secure.ThriftSecureEventTransmissionService;
import org.wso2.carbon.databridge.commons.thrift.utils.CommonThriftConstants;
import org.wso2.carbon.databridge.core.DataBridgeReceiverService;
import org.wso2.carbon.databridge.core.exception.DataBridgeException;
import org.wso2.carbon.databridge.core.internal.utils.DataBridgeConstants;
import org.wso2.carbon.databridge.receiver.thrift.conf.ThriftDataReceiverConfiguration;
import org.wso2.carbon.databridge.receiver.thrift.internal.utils.ThriftDataReceiverConstants;
import org.wso2.carbon.databridge.receiver.thrift.service.ThriftEventTransmissionServiceImpl;
import org.wso2.carbon.databridge.receiver.thrift.service.ThriftSecureEventTransmissionServiceImpl;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLServerSocket;

/**
 * Carbon based implementation of the agent server
 */
public class ThriftDataReceiver {
    private static final Log log = LogFactory.getLog(ThriftDataReceiver.class);
    private DataBridgeReceiverService dataBridgeReceiverService;
    private ThriftDataReceiverConfiguration thriftDataReceiverConfiguration;
    private TServer authenticationServer;
    private TServer dataReceiverServer;
    private int receiverStartupWaitingTime = 0;

    /**
     * Initialize Carbon Agent Server
     *
     * @param secureReceiverPort
     * @param receiverPort
     * @param dataBridgeReceiverService
     */
    public ThriftDataReceiver(int secureReceiverPort, int receiverPort,
                              DataBridgeReceiverService dataBridgeReceiverService) {
        this.dataBridgeReceiverService = dataBridgeReceiverService;
        this.thriftDataReceiverConfiguration = new ThriftDataReceiverConfiguration(secureReceiverPort, receiverPort);
    }

    /**
     * Initialize Carbon Agent Server
     *
     * @param receiverPort
     * @param dataBridgeReceiverService
     */
    public ThriftDataReceiver(int receiverPort,
                              DataBridgeReceiverService dataBridgeReceiverService) {
        this.dataBridgeReceiverService = dataBridgeReceiverService;
        this.thriftDataReceiverConfiguration = new ThriftDataReceiverConfiguration(receiverPort + CommonThriftConstants.SECURE_EVENT_RECEIVER_PORT_OFFSET, receiverPort);
    }

    /**
     * Initialize Carbon Agent Server
     *
     * @param thriftDataReceiverConfiguration
     *
     * @param dataBridgeReceiverService
     */
    public ThriftDataReceiver(ThriftDataReceiverConfiguration thriftDataReceiverConfiguration,
                              DataBridgeReceiverService dataBridgeReceiverService) {
        this.dataBridgeReceiverService = dataBridgeReceiverService;
        this.thriftDataReceiverConfiguration = thriftDataReceiverConfiguration;
    }

    /**
     * To start the Agent server
     *
     * @throws org.wso2.carbon.databridge.core.exception.DataBridgeException
     *          if the agent server cannot be started
     */
    public void start(String hostName, int receiverStartupWaitingTime)
            throws DataBridgeException {
        this.receiverStartupWaitingTime = receiverStartupWaitingTime;
        startSecureEventTransmission(hostName, thriftDataReceiverConfiguration.getSecureDataReceiverPort(),
                thriftDataReceiverConfiguration.getSslProtocols(), thriftDataReceiverConfiguration.getCiphers(), dataBridgeReceiverService);
        startEventTransmission(hostName, thriftDataReceiverConfiguration.getDataReceiverPort(), dataBridgeReceiverService);
    }

    public void start(String hostName) throws DataBridgeException {
        start(hostName, 0);
    }

    private void startSecureEventTransmission(String hostName, int port, String sslProtocols, String ciphers,
                                              DataBridgeReceiverService dataBridgeReceiverService)
            throws DataBridgeException {
        try {
            String keyStore = dataBridgeReceiverService.getInitialConfig().getKeyStoreLocation();
            if (keyStore == null) {
                ServerConfiguration serverConfig = ServerConfiguration.getInstance();
                keyStore = serverConfig.getFirstProperty("Security.KeyStore.Location");
                if (keyStore == null) {
                    keyStore = System.getProperty("Security.KeyStore.Location");
                    if (keyStore == null) {
                        throw new DataBridgeException("Cannot start thrift agent server, not valid Security.KeyStore.Location is null");
                    }
                }
            }
            String keyStorePassword = dataBridgeReceiverService.getInitialConfig().getKeyStorePassword();
            if (keyStorePassword == null) {
                ServerConfiguration serverConfig = ServerConfiguration.getInstance();
                keyStorePassword = serverConfig.getFirstProperty("Security.KeyStore.Password");
                if (keyStorePassword == null) {
                    keyStorePassword = System.getProperty("Security.KeyStore.Password");
                    if (keyStorePassword == null) {
                        throw new DataBridgeException("Cannot start thrift agent server, not valid Security.KeyStore.Password is null ");
                    }
                }
            }
            startSecureEventTransmission(hostName, port, sslProtocols, ciphers, keyStore, keyStorePassword, dataBridgeReceiverService);
        } catch (TransportException e) {
            throw new DataBridgeException("Cannot start agent server on port " + port, e);
        } catch (UnknownHostException e) {
            //ignore since localhost
        }
    }

    protected void startSecureEventTransmission(String hostName, int port, String sslProtocols, String ciphers, String keyStore,
                                                String keyStorePassword,
                                                DataBridgeReceiverService dataBridgeReceiverService)
            throws TransportException, UnknownHostException {
        TSSLTransportFactory.TSSLTransportParameters params =
                new TSSLTransportFactory.TSSLTransportParameters();
        params.setKeyStore(keyStore, keyStorePassword);

        TServerSocket serverTransport;
        try {
            InetAddress inetAddress = InetAddress.getByName(hostName);
            serverTransport = TSSLTransportFactory.getServerSocket(
                    port, DataBridgeConstants.CLIENT_TIMEOUT_MS, inetAddress, params);
            SSLServerSocket sslServerSocket = (javax.net.ssl.SSLServerSocket) serverTransport.getServerSocket();
            if (sslProtocols != null && sslProtocols.length() != 0) {
                String [] sslProtocolsArray = sslProtocols.split(",");
                sslServerSocket.setEnabledProtocols(sslProtocolsArray);
            }

            if (ciphers != null && ciphers.length() != 0) {
                String [] ciphersArray = ciphers.split(",");
                sslServerSocket.setEnabledCipherSuites(ciphersArray);
            }

            log.info("Thrift Server IP : " + hostName);
        } catch (TTransportException e) {
            throw new TransportException("Thrift transport exception occurred ", e);
        }

        ThriftSecureEventTransmissionService.Processor<ThriftSecureEventTransmissionServiceImpl> processor =
                new ThriftSecureEventTransmissionService.Processor<ThriftSecureEventTransmissionServiceImpl>(
                        new ThriftSecureEventTransmissionServiceImpl(dataBridgeReceiverService));
        TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport).processor(processor)
                .maxWorkerThreads(thriftDataReceiverConfiguration.getSslMaxWorkerThreads());
        if (thriftDataReceiverConfiguration.getSslMinWorkerThreads() != ThriftDataReceiverConstants.UNDEFINED) {
            args.minWorkerThreads = thriftDataReceiverConfiguration.getSslMinWorkerThreads();
        }
        if (thriftDataReceiverConfiguration.getSslRequestTimeout() != ThriftDataReceiverConstants.UNDEFINED) {
            args.executorService = new ThreadPoolExecutor(args.minWorkerThreads, args.maxWorkerThreads,
                    thriftDataReceiverConfiguration.getSslRequestTimeout(), TimeUnit.SECONDS,
                    new SynchronousQueue(), new ThreadFactory() {
                final AtomicLong count = new AtomicLong();

                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setDaemon(true);
                    thread.setName(String.format("TThreadPoolServer WorkerProcess-%d", this.count.getAndIncrement()));
                    return thread;
                }
            });
        }
        if (thriftDataReceiverConfiguration.getSslStopTimeoutVal() != ThriftDataReceiverConstants.UNDEFINED) {
            args.stopTimeoutVal = thriftDataReceiverConfiguration.getSslStopTimeoutVal();
        }
        authenticationServer = new TThreadPoolServer(args);

        if (log.isDebugEnabled()) {
            authenticationServer.setServerEventHandler(new LoggingServerEventHandler());
        }
        String url = hostName + ":" + port;
        Thread thread = new Thread(new ServerThread(authenticationServer, receiverStartupWaitingTime, url));
        log.info("Thrift SSL port : " + port);
        thread.start();
    }

    protected void startEventTransmission(String hostName, int port,
                                          DataBridgeReceiverService dataBridgeReceiverService)
            throws DataBridgeException {
        try {
            TServerSocket serverTransport = new TServerSocket(
                    new InetSocketAddress(hostName, port));
            ThriftEventTransmissionService.Processor<ThriftEventTransmissionServiceImpl> processor =
                    new ThriftEventTransmissionService.Processor<ThriftEventTransmissionServiceImpl>(
                            new ThriftEventTransmissionServiceImpl(dataBridgeReceiverService));
            TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport).processor(processor)
                    .maxWorkerThreads(thriftDataReceiverConfiguration.getTcpMaxWorkerThreads());
            if (thriftDataReceiverConfiguration.getTcpMinWorkerThreads() != ThriftDataReceiverConstants.UNDEFINED) {
                args.minWorkerThreads = thriftDataReceiverConfiguration.getTcpMinWorkerThreads();
            }
            if (thriftDataReceiverConfiguration.getTcpRequestTimeout() != ThriftDataReceiverConstants.UNDEFINED) {
                args.executorService = new ThreadPoolExecutor(args.minWorkerThreads, args.maxWorkerThreads,
                        thriftDataReceiverConfiguration.getTcpRequestTimeout(), TimeUnit.SECONDS,
                        new SynchronousQueue(), new ThreadFactory() {
                    final AtomicLong count = new AtomicLong();

                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setDaemon(true);
                        thread.setName(String.format("TThreadPoolServer WorkerProcess-%d", this.count.getAndIncrement()));
                        return thread;
                    }
                });
            }
            if (thriftDataReceiverConfiguration.getTcpStopTimeoutVal() != ThriftDataReceiverConstants.UNDEFINED) {
                args.stopTimeoutVal = thriftDataReceiverConfiguration.getTcpStopTimeoutVal();
            }
            dataReceiverServer = new TThreadPoolServer(args);
            String url = hostName + ":" + port;
            Thread thread = new Thread(new ServerThread(dataReceiverServer, receiverStartupWaitingTime, url));
            log.info("Thrift port : " + port);
            thread.start();
        } catch (TTransportException e) {
            throw new DataBridgeException("Cannot start Thrift server on port " + port +
                                          " on host " + hostName, e);
        }
    }

    /**
     * This event handler for thrift servers is intended to log IP and port of the peers connected to the server
     */
    static class LoggingServerEventHandler implements TServerEventHandler {
        @Override
        public void preServe() {

        }

        @Override
        public ServerContext createContext(TProtocol input, TProtocol output) {
            log.debug("Client " +  ((TSocket)input.getTransport()).getSocket().getRemoteSocketAddress().toString()
                    +" connected to thrift authentication service.");

            return null;
        }

        @Override
        public void deleteContext(ServerContext serverContext, TProtocol input, TProtocol output) {
            log.debug("Client " +  ((TSocket)input.getTransport()).getSocket().getRemoteSocketAddress().toString()
                    + " disconnected from thrift authentication service.");

        }

        @Override
        public void processContext(ServerContext serverContext, TTransport inputTransport, TTransport outputTransport) {
        }
    }


    /**
     * To stop the server
     */
    public void stop() {
        authenticationServer.stop();
        dataReceiverServer.stop();
    }

    static class ServerThread implements Runnable {
        private TServer server;
        private int waitingTime;
        private String url;
        private static final Log log = LogFactory.getLog(ServerThread.class);

        ServerThread(TServer server, int waitingTime, String url) {
            this.server = server;
            this.waitingTime = waitingTime;
            this.url = url;
        }

        public void run() {
            if (waitingTime > 0){
                try {
                    Thread.sleep(waitingTime);
                } catch (InterruptedException ignore) {}
            }
            log.info("Thrift receiver started on " + url);
            this.server.serve();
        }
    }
}

