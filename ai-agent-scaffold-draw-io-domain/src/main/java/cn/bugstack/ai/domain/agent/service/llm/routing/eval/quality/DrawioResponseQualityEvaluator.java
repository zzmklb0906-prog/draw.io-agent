package cn.bugstack.ai.domain.agent.service.llm.routing.eval.quality;

import cn.bugstack.ai.domain.agent.service.llm.routing.requirement.TaskType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * Deterministic quality evaluator for Draw.io XML generation and structure verification.
 */
@Component
public class DrawioResponseQualityEvaluator implements ResponseQualityEvaluator {

    @Override
    public boolean supports(BenchmarkCase benchmarkCase) {
        if (benchmarkCase == null || benchmarkCase.taskType() == null) return false;
        return benchmarkCase.taskType() == TaskType.DRAWIO_GENERATION
                || benchmarkCase.taskType() == TaskType.DRAWIO_REVIEW;
    }

    @Override
    public ModelQualityScore evaluate(BenchmarkCase benchmarkCase, BenchmarkRawResponse rawResponse) {
        if (rawResponse == null || !rawResponse.success() || StringUtils.isBlank(rawResponse.responseText())) {
            return ModelQualityScore.failed("Response was empty or execution failed: " + (rawResponse != null ? rawResponse.errorMessage() : "null"));
        }

        String text = rawResponse.responseText();
        String xml = extractXml(text);
        if (StringUtils.isBlank(xml)) {
            return ModelQualityScore.of(0.0, Map.of("XML_PARSING", 0.0), false, List.of("No XML markup found in response"));
        }

        List<String> issues = new ArrayList<>();
        Map<String, Double> dimensions = new LinkedHashMap<>();

        // 1. XML Parsing & Syntax (30 pts)
        Document doc = null;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            doc = db.parse(new InputSource(new StringReader(xml)));
            dimensions.put("XML_PARSING", 30.0);
        } catch (Exception e) {
            issues.add("XML parsing exception: " + e.getMessage());
            dimensions.put("XML_PARSING", 0.0);
            dimensions.put("GRAPH_STRUCTURE", 0.0);
            dimensions.put("CELL_INTEGRITY", 0.0);
            dimensions.put("EDGE_VALIDITY", 0.0);
            dimensions.put("ELEMENT_COMPLETENESS", 0.0);
            return ModelQualityScore.of(0.0, dimensions, false, issues);
        }

        // 2. Graph Model & Root Structure (20 pts)
        double graphStructureScore = 20.0;
        Element root = doc.getDocumentElement();
        boolean hasMxGraph = "mxGraphModel".equalsIgnoreCase(root.getTagName())
                || root.getElementsByTagName("mxGraphModel").getLength() > 0
                || "mxfile".equalsIgnoreCase(root.getTagName());

        if (!hasMxGraph) {
            graphStructureScore -= 15.0;
            issues.add("Missing root mxGraphModel / mxfile element");
        }
        NodeList rootElements = doc.getElementsByTagName("root");
        if (rootElements.getLength() == 0) {
            graphStructureScore -= 5.0;
            issues.add("Missing <root> container inside graph model");
        }
        dimensions.put("GRAPH_STRUCTURE", Math.max(0.0, graphStructureScore));

        // 3. mxCell IDs and Integrity (20 pts)
        double cellIntegrityScore = 20.0;
        NodeList cells = doc.getElementsByTagName("mxCell");
        Set<String> cellIds = new HashSet<>();
        List<Element> edgeCells = new ArrayList<>();
        int vertexCount = 0;

        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            String id = cell.getAttribute("id");
            if (StringUtils.isBlank(id)) {
                cellIntegrityScore -= 10.0;
                issues.add("Found mxCell with missing or blank id");
            } else if (!cellIds.add(id)) {
                cellIntegrityScore -= 15.0;
                issues.add("Duplicate mxCell id detected: " + id);
            }

            if ("1".equals(cell.getAttribute("vertex")) || "true".equalsIgnoreCase(cell.getAttribute("vertex"))) {
                vertexCount++;
            }
            if ("1".equals(cell.getAttribute("edge")) || "true".equalsIgnoreCase(cell.getAttribute("edge"))
                    || cell.hasAttribute("source") || cell.hasAttribute("target")) {
                edgeCells.add(cell);
            }
        }
        dimensions.put("CELL_INTEGRITY", Math.max(0.0, cellIntegrityScore));

        // 4. Edge Validity & Dangling References (15 pts)
        double edgeValidityScore = 15.0;
        for (Element edge : edgeCells) {
            String source = edge.getAttribute("source");
            String target = edge.getAttribute("target");
            if (StringUtils.isNotBlank(source) && !cellIds.contains(source)) {
                edgeValidityScore -= 10.0;
                issues.add("Dangling edge source reference: " + source);
            }
            if (StringUtils.isNotBlank(target) && !cellIds.contains(target)) {
                edgeValidityScore -= 10.0;
                issues.add("Dangling edge target reference: " + target);
            }
        }
        dimensions.put("EDGE_VALIDITY", Math.max(0.0, edgeValidityScore));

        // 5. Element & Required Label Completeness (15 pts)
        double completenessScore = 15.0;
        BenchmarkExpectedOutput expected = benchmarkCase.expected();
        if (expected != null) {
            if (expected.minElementCount() != null && vertexCount < expected.minElementCount()) {
                completenessScore -= 5.0;
                issues.add(String.format("Vertex count (%d) is below expected minimum (%d)", vertexCount, expected.minElementCount()));
            }
            if (expected.minEdgeCount() != null && edgeCells.size() < expected.minEdgeCount()) {
                completenessScore -= 5.0;
                issues.add(String.format("Edge count (%d) is below expected minimum (%d)", edgeCells.size(), expected.minEdgeCount()));
            }
            if (expected.requiredElements() != null && !expected.requiredElements().isEmpty()) {
                String fullXmlLower = xml.toLowerCase();
                for (String req : expected.requiredElements()) {
                    if (!fullXmlLower.contains(req.toLowerCase())) {
                        completenessScore -= (5.0 / expected.requiredElements().size());
                        issues.add("Missing required diagram element/label: " + req);
                    }
                }
            }
        }
        dimensions.put("ELEMENT_COMPLETENESS", Math.max(0.0, completenessScore));

        double totalScore = dimensions.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean passed = totalScore >= 70.0 && issues.isEmpty();

        return ModelQualityScore.of(totalScore, dimensions, passed, issues);
    }

    private String extractXml(String text) {
        if (StringUtils.isBlank(text)) return "";
        int start = text.indexOf("<mxGraphModel");
        if (start < 0) start = text.indexOf("<mxfile");
        if (start < 0) start = text.indexOf("<?xml");
        if (start < 0) return "";

        int end = text.lastIndexOf("</mxGraphModel>");
        if (end >= 0) {
            return text.substring(start, end + "</mxGraphModel>".length());
        }
        end = text.lastIndexOf("</mxfile>");
        if (end >= 0) {
            return text.substring(start, end + "</mxfile>".length());
        }
        return text.substring(start);
    }
}
