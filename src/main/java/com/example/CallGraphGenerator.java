package com.example;

import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Location;
import com.google.perftools.profiles.ProfileProto.Sample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class CallGraphGenerator {
    private final Profile profile;
    private final List<String> stringTable;
    private final String outputPath;

    public CallGraphGenerator(Profile profile, String outputPath) {
        this.profile = profile;
        this.stringTable = profile.getStringTableList();
        this.outputPath = outputPath;
    }

    public void generateCallGraph() throws IOException {
        // 构建调用关系图
        Map<String, Set<String>> callGraph = buildCallGraph();
        Map<String, Long> functionCumTime = calculateFunctionCumTime();
        Map<String, Long> functionSelfTime = calculateFunctionSelfTime();
        
        // 生成SVG调用图
        generateSvg(callGraph, functionCumTime, functionSelfTime);
    }

    private Map<String, Set<String>> buildCallGraph() {
        Map<String, Set<String>> callGraph = new HashMap<>();
        List<Sample> samples = profile.getSampleList();

        for (Sample sample : samples) {
            List<String> callStack = new ArrayList<>();
            
            // 从底部到顶部构建调用栈
            for (int i = sample.getLocationIdCount() - 1; i >= 0; i--) {
                Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    callStack.add(functionName);
                }
            }

            // 构建调用关系
            for (int i = 0; i < callStack.size() - 1; i++) {
                String caller = callStack.get(i);
                String callee = callStack.get(i + 1);
                
                callGraph.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
            }
        }

        return callGraph;
    }

    private Map<String, Long> calculateFunctionCumTime() {
        Map<String, Long> functionCumTime = new HashMap<>();
        List<Sample> samples = profile.getSampleList();

        for (Sample sample : samples) {
            long cum = sample.getValueList().get(1); // 获取累积时间
            for (long locationId : sample.getLocationIdList()) {
                Location location = profile.getLocation((int)locationId - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    functionCumTime.merge(functionName, cum, Long::sum);
                }
            }
        }

        return functionCumTime;
    }

    private Map<String, Long> calculateFunctionSelfTime() {
        Map<String, Long> functionSelfTime = new HashMap<>();
        List<Sample> samples = profile.getSampleList();

        for (Sample sample : samples) {
            long self = sample.getValueList().get(0); // 获取自身时间
            if (!sample.getLocationIdList().isEmpty()) {
                Location location = profile.getLocation((int)sample.getLocationId(0) - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    functionSelfTime.merge(functionName, self, Long::sum);
                }
            }
        }

        return functionSelfTime;
    }

    private void generateSvg(Map<String, Set<String>> callGraph, Map<String, Long> functionCumTime,
            Map<String, Long> functionSelfTime) throws IOException {
        try (FileWriter writer = new FileWriter(outputPath)) {
            int width = 1200;
            int height = 800;
            int padding = 40;

            writer.write(String.format("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"));
            writer.write(String.format("<svg width=\"%d\" height=\"%d\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\">\n",
                width, height));

            // 添加标题和样式
            writer.write(String.format("<style>\n"));
            writer.write(String.format(".title { font-size: 16px; font-weight: bold; font-family: monospace; }\n"));
            writer.write(String.format(".subtitle { font-size: 12px; font-family: monospace; fill: #666; }\n"));
            writer.write(String.format(".node { cursor: pointer; }\n"));
            writer.write(String.format(".node:hover { opacity: 0.8; }\n"));
            writer.write(String.format(".node-text { font-size: 11px; font-family: monospace; pointer-events: none; }\n"));
            writer.write(String.format(".edge { stroke: #999; stroke-opacity: 0.6; stroke-width: 1.5px; marker-end: url(#arrowhead); }\n"));
            writer.write(String.format(".edge:hover { stroke-opacity: 1; }\n"));
            writer.write(String.format("</style>\n"));

            // 添加箭头标记定义
            writer.write(String.format("<defs>\n"));
            writer.write(String.format("  <marker id=\"arrowhead\" viewBox=\"0 -3 6 6\" refX=\"6\" refY=\"0\"\n"));
            writer.write(String.format("          markerWidth=\"4\" markerHeight=\"4\" orient=\"auto\">\n"));
            writer.write(String.format("    <path d=\"M0,-3L6,0L0,3\" fill=\"#999\"/>\n"));
            writer.write(String.format("  </marker>\n"));
            writer.write(String.format("</defs>\n"));

            // 添加标题和性能统计信息
            writer.write(String.format("<text x=\"%d\" y=\"20\" class=\"title\">Type: cpu</text>\n", padding));
            writer.write(String.format("<text x=\"%d\" y=\"35\" class=\"subtitle\">%d functions, %d edges</text>\n",
                padding, callGraph.size(), callGraph.values().stream().mapToInt(Set::size).sum()));

            // 计算节点位置
            Map<String, double[]> nodePositions = calculateNodePositions(callGraph, width, height, padding);

            // 绘制边
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String caller = entry.getKey();
                double[] callerPos = nodePositions.get(caller);

                for (String callee : entry.getValue()) {
                    double[] calleePos = nodePositions.get(callee);
                    if (callerPos != null && calleePos != null) {
                        // 计算箭头的偏移量，避免箭头与节点重叠
                        double dx = calleePos[0] - callerPos[0];
                        double dy = calleePos[1] - callerPos[1];
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        double radius = 5 + (15.0 * functionCumTime.getOrDefault(callee, 0L) / Collections.max(functionCumTime.values()));
                        
                        if (dist > 0) {
                            double offsetX = (dx / dist) * radius;
                            double offsetY = (dy / dist) * radius;
                            
                            writer.write(String.format("<line class=\"edge\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" />\n",
                                callerPos[0], callerPos[1],
                                calleePos[0] - offsetX, calleePos[1] - offsetY));
                        }
                    }
                }
            }

            // 绘制节点
            long maxTime = Collections.max(functionCumTime.values());
            for (Map.Entry<String, double[]> entry : nodePositions.entrySet()) {
                String function = entry.getKey();
                double[] pos = entry.getValue();
                long cumTime = functionCumTime.getOrDefault(function, 0L);
                long selfTime = functionSelfTime.getOrDefault(function, 0L);
                
                // 根据累积时间计算节点大小和颜色
                double radius = 5 + (15.0 * cumTime / maxTime);
                String color = String.format("#%02x%02x%02x",
                    255, 150 + (int)(105.0 * cumTime / maxTime), 150 + (int)(105.0 * cumTime / maxTime));

                writer.write(String.format("<g class=\"node\" transform=\"translate(%.1f,%.1f)\">\n", pos[0], pos[1]));
                writer.write(String.format("<circle r=\"%.1f\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"1\" />\n", radius, color));
                writer.write(String.format("<title>%s\n累积: %.2fms (%.1f%%)\n自身: %.2fms (%.1f%%)\n</title>\n",
                    function,
                    cumTime / 1_000_000.0, 100.0 * cumTime / maxTime,
                    selfTime / 1_000_000.0, 100.0 * selfTime / maxTime));
                writer.write(String.format("<text x=\"%.1f\" y=\"%.1f\" class=\"node-text\" text-anchor=\"middle\">%s</text>\n",
                    0.0, radius + 12, truncateText(function, 20)));
                writer.write("</g>\n");
            }

            writer.write("</svg>\n");
        }
    }

    private Map<String, double[]> calculateNodePositions(Map<String, Set<String>> callGraph,
            int width, int height, int padding) {
        Map<String, double[]> positions = new HashMap<>();
        Set<String> allNodes = new HashSet<>();
        
        // 收集所有节点
        callGraph.forEach((caller, callees) -> {
            allNodes.add(caller);
            allNodes.addAll(callees);
        });

        // 使用力导向布局算法计算节点位置
        int n = allNodes.size();
        List<String> nodeList = new ArrayList<>(allNodes);
        Random random = new Random(42); // 固定随机种子以获得一致的布局

        // 初始化随机位置
        for (String node : nodeList) {
            positions.put(node, new double[] {
                padding + random.nextDouble() * (width - 2 * padding),
                padding + random.nextDouble() * (height - 2 * padding)
            });
        }

        // 力导向布局迭代
        double k = Math.sqrt((width - 2 * padding) * (height - 2 * padding) / n); // 理想边长
        int iterations = 100;
        double temperature = 0.1 * Math.min(width, height); // 初始温度
        double minTemp = 1.0; // 最小温度

        for (int iter = 0; iter < iterations && temperature > minTemp; iter++) {
            // 计算斥力
            Map<String, double[]> forces = new HashMap<>();
            for (int i = 0; i < n; i++) {
                String v1 = nodeList.get(i);
                double[] pos1 = positions.get(v1);
                double[] force = new double[2];

                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        String v2 = nodeList.get(j);
                        double[] pos2 = positions.get(v2);
                        double dx = pos1[0] - pos2[0];
                        double dy = pos1[1] - pos2[1];
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        if (dist > 0) {
                            double f = k * k / dist; // 斥力大小
                            force[0] += f * dx / dist;
                            force[1] += f * dy / dist;
                        }
                    }
                }
                forces.put(v1, force);
            }

            // 计算引力
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                String v1 = entry.getKey();
                double[] pos1 = positions.get(v1);
                double[] force = forces.get(v1);

                for (String v2 : entry.getValue()) {
                    double[] pos2 = positions.get(v2);
                    double dx = pos1[0] - pos2[0];
                    double dy = pos1[1] - pos2[1];
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > 0) {
                        double f = dist * dist / k; // 引力大小
                        force[0] -= f * dx / dist;
                        force[1] -= f * dy / dist;
                    }
                }
            }

            // 更新位置
            for (String node : nodeList) {
                double[] pos = positions.get(node);
                double[] force = forces.get(node);
                double dist = Math.sqrt(force[0] * force[0] + force[1] * force[1]);
                if (dist > 0) {
                    double d = Math.min(dist, temperature) / dist;
                    pos[0] += force[0] * d;
                    pos[1] += force[1] * d;

                    // 确保节点不会超出边界
                    pos[0] = Math.max(padding, Math.min(width - padding, pos[0]));
                    pos[1] = Math.max(padding, Math.min(height - padding, pos[1]));
                }
            }

            temperature *= 0.95; // 降温
        }

        return positions;
    }

    private String truncateText(String text, int maxLength) {
        if (text.length() > maxLength) {
            return text.substring(0, maxLength - 3) + "...";
        }
        return text;
    }
}