package com.ugcleague.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ugcleague.ops.service.discord.CommandService;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestOperations;
import sx.blah.discord.handle.obj.IMessage;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);

    @PersistenceContext
    private final EntityManager entityManager;
    private final ApplicationContext context;
    private final DiscordService discordService;
    private final GameServerService gameServerService;
    private final PermissionService permissionService;
    private final CommandService commandService;
    private final ObjectMapper mapper;
    private final RestOperations restTemplate;

    @Autowired
    public ScriptService(ApplicationContext context, EntityManager entityManager, DiscordService discordService,
                         GameServerService gameServerService, PermissionService permissionService,
                         CommandService commandService, ObjectMapper mapper, RestOperations restTemplate) {
        this.context = context;
        this.entityManager = entityManager;
        this.discordService = discordService;
        this.gameServerService = gameServerService;
        this.permissionService = permissionService;
        this.commandService = commandService;
        this.mapper = mapper;
        this.restTemplate = restTemplate;
    }

    private GroovyShell createShell(Map<String, Object> bindingValues) {
        bindingValues.put("context", context);
        bindingValues.put("entityManager", entityManager);
        bindingValues.put("discord", discordService);
        bindingValues.put("gameServer", gameServerService);
        bindingValues.put("permission", permissionService);
        bindingValues.put("client", discordService.getClient());
        bindingValues.put("bot", discordService.getClient().getOurUser());
        bindingValues.put("mapper", mapper);
        bindingValues.put("rest", restTemplate);
        bindingValues.put("commands", commandService);
        return new GroovyShell(this.getClass().getClassLoader(), new Binding(bindingValues));
    }

    public Map<String, Object> executeScript(String script, IMessage message) {
        log.info("Executing Script: " + script);
        try {
            return eval(script, message);
        } catch (Throwable t) {
            log.warn("Error while evaluating script", t);
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("error", t.getMessage());
            return resultMap;
        }
    }

    public Map<String, Object> eval(String script, IMessage message) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("script", script);
        resultMap.put("startTime", ZonedDateTime.now());

//        SystemOutputInterceptorClosure outputCollector = new SystemOutputInterceptorClosure(null);
//        SystemOutputInterceptor systemOutputInterceptor = new SystemOutputInterceptor(outputCollector);
//        systemOutputInterceptor.start();

        try {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("message", message);
            scope.put("server", message.getChannel().isPrivate() ? null : message.getChannel().getGuild());
            scope.put("channel", message.getChannel());
            scope.put("author", message.getAuthor());
            FutureTask<String> evalTask = new FutureTask<>(() -> evalWithScope(script, scope));
            evalTask.run();
            resultMap.put("result", evalTask.get(1, TimeUnit.MINUTES));
        } catch (Throwable t) {
            log.error("Could not bind result", t);
            resultMap.put("error", t.getMessage());
        }
//        } finally {
//            systemOutputInterceptor.stop();
//        }
//
//        String output = outputCollector.getStringBuffer().toString().trim();
//        StringBuilder builder = new StringBuilder();
//        for (String line : output.split("\n")) {
//            if (!line.isEmpty()) {
//                String[] tokens = line.split("\\[0;39m ");
//                builder.append(tokens[tokens.length - 1]).append("\n");
//            }
//        }
//        resultMap.put("output", builder.toString());
        resultMap.put("endTime", ZonedDateTime.now());
        return resultMap;
    }

    public String evalWithScope(String script, Map<String, Object> scope) {
        Object result = createShell(scope).evaluate(script);
        String resultString = result != null ? result.toString() : "null";
        log.trace("eval() result: " + resultString);
        return resultString;
    }
}
