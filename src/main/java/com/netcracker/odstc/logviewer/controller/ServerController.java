package com.netcracker.odstc.logviewer.controller;

import com.netcracker.odstc.logviewer.models.Server;
import com.netcracker.odstc.logviewer.models.User;
import com.netcracker.odstc.logviewer.serverconnection.services.ServerConnectionService;
import com.netcracker.odstc.logviewer.service.ServerService;
import com.netcracker.odstc.logviewer.service.UserService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.security.Principal;

@RequestMapping("/api/server")
@RestController
public class ServerController {

    private final ServerService serverService;
    private final UserService userService;
    private static final String DEFAULT_PAGE_SIZE = "10";
    private final Logger logger = LogManager.getLogger(ServerController.class.getName());

    public ServerController(ServerService serverService, UserService userService) {
        this.serverService = serverService;
        this.userService = userService;
    }

    @GetMapping("/")
    public Page<Server> showAllServers(Principal principal,
                                       @RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "pageSize", defaultValue = DEFAULT_PAGE_SIZE) int pageSize) {
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        User user = userService.findByLogin(principal.getName());
        return serverService.showAllServersByPagination(pageRequest, user);
    }

    @PostMapping("/add")
    public ResponseEntity<BigInteger> add(@RequestBody Server server, Principal principal) {
        User user = userService.findByLogin(principal.getName());
        serverService.add(server, user.getObjectId());
        logger.info("Server added");
        return ResponseEntity.ok(server.getObjectId());
    }

    @PutMapping("/update")
    public ResponseEntity<Server> update(@RequestBody Server server) {
        serverService.update(server);
        logger.info("Server update");
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Server> findById(@PathVariable BigInteger id) {
        Server server = serverService.findById(id);
        return ResponseEntity.ok(server);
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Server> deleteById(@PathVariable BigInteger id) {
        serverService.deleteById(id);
        logger.info("Server delete");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/testConnection")
    public ResponseEntity<Boolean> testConnection(@RequestBody Server server) {
        return ResponseEntity.ok(ServerConnectionService.getInstance().isServerAvailable(server));
    }

}

