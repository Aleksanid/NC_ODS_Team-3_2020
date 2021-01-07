package com.netcracker.odstc.logviewer.serverconnection.managers;

import com.netcracker.odstc.logviewer.containers.HierarchyContainer;
import com.netcracker.odstc.logviewer.dao.ContainerDAO;
import com.netcracker.odstc.logviewer.models.Config;
import com.netcracker.odstc.logviewer.models.Directory;
import com.netcracker.odstc.logviewer.models.Log;
import com.netcracker.odstc.logviewer.models.LogFile;
import com.netcracker.odstc.logviewer.models.Server;
import com.netcracker.odstc.logviewer.models.eaventity.constants.ObjectTypes;
import com.netcracker.odstc.logviewer.serverconnection.ServerConnection;
import com.netcracker.odstc.logviewer.serverconnection.exceptions.ServerConnectionException;
import com.netcracker.odstc.logviewer.serverconnection.publishers.DAOChangeListener;
import com.netcracker.odstc.logviewer.serverconnection.publishers.DAOPublisher;
import com.netcracker.odstc.logviewer.serverconnection.publishers.ObjectChangeEvent;
import com.netcracker.odstc.logviewer.serverconnection.services.ServerConnectionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class ServerManager implements DAOChangeListener {
    private final Logger logger = LogManager.getLogger(ServerManager.class.getName());

    private final ContainerDAO containerDAO;

    private final Map<ObjectTypes, List<BigInteger>> iterationRemove;
    private final Map<BigInteger, ServerConnection> serverConnections;

    private final ServerPollManager serverPollManager;
    private final ServerConnectionService serverConnectionService;

    private final ScheduledExecutorService runnableService;

    @SuppressWarnings({"squid:S1144"})
//Suppress unused private constructor: Spring will use this constructor, even if it private
    private ServerManager(ContainerDAO containerDAO) {
        this.containerDAO = containerDAO;
        DAOPublisher.getInstance().addListener(this);

        serverConnectionService = ServerConnectionService.getInstance();
        serverPollManager = ServerPollManager.getInstance();

        serverConnections = new ConcurrentHashMap<>();
        iterationRemove = new EnumMap<>(ObjectTypes.class);

        iterationRemove.put(ObjectTypes.SERVER, new ArrayList<>());
        iterationRemove.put(ObjectTypes.DIRECTORY, new ArrayList<>());
        iterationRemove.put(ObjectTypes.LOGFILE, new ArrayList<>());

        runnableService = Executors.newSingleThreadScheduledExecutor();
        startRunnables();
    }

    @Override
    public void objectChanged(ObjectChangeEvent objectChangeEvent) {
        if (objectChangeEvent.getChangeType() == ObjectChangeEvent.ChangeType.DELETE) {
            BigInteger objectTypeId = (BigInteger) objectChangeEvent.getArgument();
            if (objectTypeId.equals(ObjectTypes.USER.getObjectTypeID()) || objectTypeId.equals(ObjectTypes.LOG.getObjectTypeID()))
                return;
            BigInteger objectId = (BigInteger) objectChangeEvent.getObject();
            iterationRemove.get(ObjectTypes.getObjectTypesByObjectTypeId(objectTypeId)).add(objectId);
        } else if (objectChangeEvent.getChangeType() == ObjectChangeEvent.ChangeType.UPDATE) {
            if (Server.class.isAssignableFrom(objectChangeEvent.getObject().getClass())) {
                serverChanged(objectChangeEvent);
            } else if (Directory.class.isAssignableFrom(objectChangeEvent.getObject().getClass())) {
                directoryChanged(objectChangeEvent);
            }
        }
    }

    private void startRunnables() {
        Config configInstance = containerDAO.getObjectById(BigInteger.ZERO, Config.class);
        Config.setInstance(configInstance);

        logger.info("Starting Polling runnable");
        runnableService.scheduleWithFixedDelay(this::getLogsFromAllServers, 0, configInstance.getChangesPollingPeriod(), TimeUnit.MILLISECONDS);
        logger.info("Polling runnable started");
        logger.info("Starting activity check runnable");
        runnableService.scheduleAtFixedRate(this::revalidateServers, 0, configInstance.getActivityPollingPeriod(), TimeUnit.MILLISECONDS);
        logger.info("Activity check runnable started");
    }

    private void getLogsFromAllServers() {
        List<Log> result = new ArrayList<>(serverPollManager.getLogsFromThreads());
        containerDAO.saveObjectsAttributesReferences(result);
        if (!serverPollManager.getServerConnectionsResults().isEmpty()) {
            logger.warn("Skipping job due to previous is not finished");
            return;
        }
        savePollResults();
        updateActiveServersFromDB();
        startPoll();
    }

    private void revalidateServers() {
        List<HierarchyContainer> servers = containerDAO.getNonactiveServers();
        List<Server> serversToSave = new ArrayList<>();
        for (HierarchyContainer serverContainer : servers) {
            ServerConnection serverConnection = serverConnectionService.wrapServerIntoConnection(serverContainer);
            if (serverConnection == null) continue;
            try {
                serverConnection.connect();
                serverConnection.disconnect();
            } catch (ServerConnectionException e) {
                logger.error(e);
            }
            serversToSave.add(serverConnection.getServer());
        }
        containerDAO.saveObjectsAttributesReferences(serversToSave);
        revalidateActiveServersDirectories();
    }

    private void revalidateActiveServersDirectories() {
        List<HierarchyContainer> servers = containerDAO.getActiveServersWithNonactiveDirectories();
        List<Directory> directories = new ArrayList<>();

        for (HierarchyContainer serverContainer : servers) {
            ServerConnection serverConnection = serverConnectionService.wrapServerIntoConnection(serverContainer);
            if (serverConnection == null) continue;
            serverConnection.setDirectories(serverContainer.getChildren());
            try {
                serverConnection.revalidateDirectories();
            } catch (ServerConnectionException e) {
                logger.error(e);
            }
            for (HierarchyContainer directoryContainer : serverConnection.getDirectories()) {
                directories.add((Directory) directoryContainer.getOriginal());
            }
        }
        containerDAO.saveObjectsAttributesReferences(directories);
    }

    private void directoryChanged(ObjectChangeEvent objectChangeEvent) {
        Directory directory = (Directory) objectChangeEvent.getObject();
        if (!serverConnections.containsKey(directory.getParentId()))
            return;
        ServerConnection serverConnection = serverConnections.get(directory.getParentId());
        if (directory.isEnabled()) {
            serverConnection.updateDirectory(directory);
        }
    }

    private void serverChanged(ObjectChangeEvent objectChangeEvent) {
        Server server = (Server) objectChangeEvent.getObject();
        if (!server.isEnabled() && serverConnections.containsKey(server.getObjectId())) {
            serverConnections.remove(server.getObjectId());
        } else if (serverConnections.containsKey(server.getObjectId())) {
            ServerConnection serverConnection = serverConnections.get(server.getObjectId());
            serverConnection.setServer(server);
        }
    }

    private void savePollResults() {
        excludeRemoved();
        List<Server> servers = new ArrayList<>(serverPollManager.getFinishedServers().size());
        List<Directory> directories = new ArrayList<>();
        List<LogFile> logFiles = new ArrayList<>();
        for (ServerConnection serverConnection : serverPollManager.getFinishedServers().values()) {
            servers.add(serverConnection.getServer());
            for (HierarchyContainer directoryContainer : serverConnection.getDirectories()) {
                directories.add((Directory) directoryContainer.getOriginal());
                for (HierarchyContainer logFileContainer : directoryContainer.getChildren()) {
                    logFiles.add((LogFile) logFileContainer.getOriginal());
                }
            }
        }
        clearIterationInfo();
        containerDAO.saveObjectsAttributesReferences(servers);
        containerDAO.saveObjectsAttributesReferences(directories);
        containerDAO.saveObjectsAttributesReferences(logFiles);
    }

    private void excludeRemoved() {
        for (Iterator<ServerConnection> serverConnectionIterator = serverConnections.values().iterator(); serverConnectionIterator.hasNext(); ) {
            ServerConnection serverConnection = serverConnectionIterator.next();
            if (iterationRemove.get(ObjectTypes.SERVER).contains(serverConnection.getServer().getObjectId())) {
                serverConnectionIterator.remove();
                continue;
            }
            for (Iterator<HierarchyContainer> directoryIterator = serverConnection.getDirectories().iterator(); directoryIterator.hasNext(); ) {
                HierarchyContainer directoryContainer = directoryIterator.next();
                if (iterationRemove.get(ObjectTypes.DIRECTORY).contains(directoryContainer.getOriginal().getObjectId())) {
                    directoryIterator.remove();
                    continue;
                }
                for (Iterator<HierarchyContainer> logFileIterator = directoryContainer.getChildren().iterator(); logFileIterator.hasNext(); ) {
                    HierarchyContainer logFileContainer = logFileIterator.next();
                    if (iterationRemove.get(ObjectTypes.LOGFILE).contains(logFileContainer.getOriginal().getObjectId())) {
                        logFileIterator.remove();
                    }
                }
            }
        }
        clearIterationInfo();
    }

    private void updateActiveServersFromDB() {
        List<HierarchyContainer> serverContainers = containerDAO.getActiveServersWithChildren();
        logger.info("Active Servers in DB: {}", serverContainers.size());
        logger.info("Active Servers in Poll: {}", serverConnections.size());
        for (HierarchyContainer serverContainer : serverContainers) {
            Server server = (Server) serverContainer.getOriginal();
            if (serverConnections.containsKey(server.getObjectId())) {
                serverConnections.get(server.getObjectId()).setServer(server);
                serverConnections.get(server.getObjectId()).setDirectories(serverContainer.getChildren());
            } else {
                logger.info("Adding new server to poll: {}", server.getIp());
                ServerConnection serverConnection = serverConnectionService.wrapServerIntoConnection(serverContainer);
                if (serverConnection == null) continue;
                serverConnection.setDirectories(serverContainer.getChildren());
                serverConnections.put(server.getObjectId(), serverConnection);
            }
        }
        serverPollManager.getFinishedServers().clear();
    }

    private void startPoll() {
        Iterator<ServerConnection> serverConnectionIterator = serverConnections.values().iterator();
        while (serverConnectionIterator.hasNext()) {
            ServerConnection serverConnection = serverConnectionIterator.next();
            if (serverConnection.getServer().isConnectable()) {
                serverPollManager.executeExtractingLogs(serverConnection);
            } else {
                serverConnectionIterator.remove();
            }
        }
    }

    private void clearIterationInfo() {
        iterationRemove.get(ObjectTypes.SERVER).clear();
        iterationRemove.get(ObjectTypes.DIRECTORY).clear();
        iterationRemove.get(ObjectTypes.LOGFILE).clear();
    }
}