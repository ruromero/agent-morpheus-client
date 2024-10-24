package com.redhat.ecosystemappeng.morpheus.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.ecosystemappeng.morpheus.model.Justification;
import com.redhat.ecosystemappeng.morpheus.model.Report;
import com.redhat.ecosystemappeng.morpheus.model.VulnResult;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReportService {

  private static final Logger LOGGER = Logger.getLogger(ReportService.class);

  @ConfigProperty(name = "morpheus.reports.path")
  String reportsPath;

  @Inject
  ObjectMapper mapper;

  private Map<String, Report> reports = new HashMap<>();

  private String getId(JsonNode obj) {
    var input = obj.get("input");
    var idNode = input.get("scan").get("id");
    if (!idNode.isNull() && !idNode.asText().isBlank()) {
      return idNode.asText();
    }
    var name = input.get("image").get("name").asText();
    var tag = input.get("image").get("tag").asText();
    if (tag.startsWith("sha256")) {
      tag = tag.substring(0, 15);
    }
    var id = name + ":" + tag;
    return id.replace("/", "_");
  }

  private String getIdSuffix(String id) {
    var counter = 1;
    if (id.matches(".*\\.d+")) {
      id = id.substring(0, id.lastIndexOf("."));
    }
    while (reports.containsKey(id + "." + counter)) {
      counter++;
    }
    return id + "." + counter;
  }

  @Startup
  void init() {
    loadReports();
  }

  public Report save(String data) throws IOException {
    var obj = mapper.readTree(data);
    var scan = (ObjectNode) obj.get("input").get("scan");
    var reportId = scan.get("id").asText();
    var id = getId(obj);
    if (reports.containsKey(id)) {
      id = getIdSuffix(id);
    }
    var fileName = id + ".json";
    var r = toReport(id, obj, fileName);
    if (!Objects.equals(reportId, r.id()) || !Objects.equals(r.id(), id)) {
      scan.put("id", r.id());
    }
    Files.writeString(Path.of(reportsPath, fileName), mapper.writeValueAsString(obj));
    reports.put(id, r);
    return r;
  }

  public Collection<Report> list() {
    if (reports.isEmpty()) {
      loadReports();
    }
    return reports.values();
  }

  public String get(String id) throws IOException {
    if (!reports.containsKey(id)) {
      return null;
    }

    var path = Paths.get(reportsPath, reports.get(id).filePath()).toString();
    return mapper.readTree(new File(path)).toPrettyString();

  }

  public void remove(String id) throws IOException {
    if (!reports.containsKey(id)) {
      return;
    }
    var path = Paths.get(reportsPath, reports.get(id).filePath());
    Files.deleteIfExists(path);
    reports.remove(id);
  }

  private Report toReport(File f) {
    try {
      var obj = mapper.readTree(f);
      return toReport(getId(obj), obj, f.getName());
    } catch (Exception e) {
      LOGGER.warnf("Unable to read JSON Report from file: %s", f.getName(), e);
      return null;
    }
  }

  private Report toReport(String id, JsonNode obj, String fileName) {
    try {
      var input = obj.get("input");
      var scan = input.get("scan");
      var image = input.get("image");
      ArrayNode output = (ArrayNode) obj.get("output");
      Set<VulnResult> cves = new HashSet<>();
      var iterator = output.iterator();
      while (iterator.hasNext()) {
        var vuln = iterator.next();
        var vulnId = vuln.get("vuln_id").asText();
        String vulnComments = null;
        if (vuln.has("vuln_comments")) {
          vulnComments = vuln.get("vuln_comments").asText();
        }
        var status = vuln.get("justification").get("status").asText();
        var label = vuln.get("justification").get("label").asText();
        cves.add(new VulnResult(vulnId, vulnComments, new Justification(status, label)));
      }
      return new Report(id, scan.get("started_at").asText(),
          scan.get("completed_at").asText(), image.get("name").asText(),
          image.get("tag").asText(), cves, fileName);
    } catch (Exception e) {
      LOGGER.infof("Ignoring invalid file: %s", fileName);
      return null;
    }
  }

  private boolean isOutput(Path file) {
    return !Files.isDirectory(file) && file.getFileName().toString().endsWith(".json");
  }

  private void loadReports() {
    try {
      try (Stream<Path> stream = Files.list(Paths.get(reportsPath))) {

        reports.putAll(stream.filter(this::isOutput)
            .map(Path::toFile)
            .map(this::toReport)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(r -> r.id(), r -> r, (first, second) -> first)));
      }
    } catch (IOException e) {
      LOGGER.warn("Unable to traverse reports directory", e);
    }
  }
}
