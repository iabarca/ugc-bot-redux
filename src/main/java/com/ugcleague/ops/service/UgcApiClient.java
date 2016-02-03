package com.ugcleague.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ugcleague.ops.web.rest.JsonUgcResponse;
import com.ugcleague.ops.config.LeagueProperties;
import com.ugcleague.ops.domain.document.UgcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class UgcApiClient {

    private static final Logger log = LoggerFactory.getLogger(UgcApiClient.class);

    private final LeagueProperties leagueProperties;
    private final ObjectMapper mapper;

    private String teamRosterUrl;
    private String teamPageUrl;
    private String matchResultsUrl;
    private String existsUrl;
    private String playerTeamHistoryUrl;
    private String playerTeamCurrentUrl;
    private String playerTeamCurrentActiveUrl;

    @Autowired
    public UgcApiClient(LeagueProperties leagueProperties, ObjectMapper mapper) {
        this.leagueProperties = leagueProperties;
        this.mapper = mapper;
    }

    @PostConstruct
    private void configure() {
        Map<String, String> endpoints = leagueProperties.getUgc().getEndpoints();
        // load endpoints
        // v1 API
        teamRosterUrl = endpoints.get("teamRoster"); // clan_id
        teamPageUrl = endpoints.get("teamPage"); // clan_id
        matchResultsUrl = endpoints.get("matchResults"); // week, season
        // v2 API
        existsUrl = endpoints.get("exists"); // key, id64
        playerTeamHistoryUrl = endpoints.get("playerTeamHistory"); // key, id64
        playerTeamCurrentUrl = endpoints.get("playerTeamCurrent"); // key, id64
        playerTeamCurrentActiveUrl = endpoints.get("playerTeamCurrentActive"); // id64
    }

    @Retryable(backoff = @Backoff(2000))
    private String httpToString(String url) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Content-length", "0");
        c.setUseCaches(false);
        c.setAllowUserInteraction(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/48.0.2564.97 Safari/537.36");
        c.connect();
        int status = c.getResponseCode();
        switch (status) {
            case 200:
            case 201:
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                }
            case 403:
            case 404:
                throw new IOException("Returned status: " + status);
        }
        return "";
    }

    public Set<UgcResult> getMatchResults(int season, int week) {
        return rawMatchResults(season, week).map(this::mapMatchResults).orElseGet(LinkedHashSet::new);
    }

    private Set<UgcResult> mapMatchResults(JsonUgcResponse response) {
        Set<UgcResult> results = new LinkedHashSet<>();
        for (List<Object> raw : response.getData()) {
            UgcResult result = new UgcResult();
            result.setMatchId((Integer) raw.get(0)); // "MATCH_ID", #
            result.setScheduleId((Integer) raw.get(1)); // "SCHED_ID", #
            result.setScheduleDate(parseLongDate((String) raw.get(2))); // "SCHED_DT", date
            result.setMapName((String) raw.get(3)); // "MAP_NAME", str
            result.setHomeClanId((Integer) raw.get(4)); // "CLAN_ID_H", #
            result.setHomeTeam((String) raw.get(5)); // "HOME_TEAM", str
            result.setAwayClanId((Integer) raw.get(6)); // "CLAN_ID_V", #
            int h1 = (int) raw.get(7); // "NO_SCORE_R1_H", #
            int h2 = (int) raw.get(8); // "NO_SCORE_R2_H", #
            int h3 = (int) raw.get(9); // "NO_SCORE_R3_H", #
            result.setHomeScores(Arrays.asList(h1, h2, h3));
            result.setAwayTeam((String) raw.get(10)); // "VISITING_TEAM", str
            int a1 = (int) raw.get(11); // "NO_SCORE_R1_V", #
            int a2 = (int) raw.get(12); // "NO_SCORE_R2_V", #
            int a3 = (int) raw.get(13); // "NO_SCORE_R3_V", #
            result.setAwayScores(Arrays.asList(a1, a2, a3));
            result.setWinnerClanId((Integer) raw.get(14)); // "WINNER", #
            result.setWinner((String) raw.get(15)); // "WINNING_TEAM" str
            result.setId(result.getMatchId());
            results.add(result);
        }
        return results;
    }

    private Optional<JsonUgcResponse> rawMatchResults(int season, int week) {
        String url = matchResultsUrl.replace("{week}", week + "").replace("{season}", season + "");
        try {
            return Optional.of(mapper.readValue(httpToString(url), JsonUgcResponse.class));
        } catch (IOException e) {
            log.warn("Could not get s{}w{} results: {}", season, week, e.toString());
        }
        return Optional.empty();
    }

    private ZonedDateTime parseLongDate(String text) {
        try {
            LocalDateTime actual = LocalDateTime.parse(text, DateTimeFormatter.ofPattern("MMMM, dd yyyy HH:mm:ss", Locale.ENGLISH));
            return actual.atZone(ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            log.warn("Could not extract date from {}: {}", text, e.toString());
        }
        return ZonedDateTime.now();
    }
}