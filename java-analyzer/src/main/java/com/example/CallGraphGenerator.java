package com.example;

import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Location;
import com.google.perftools.profiles.ProfileProto.Sample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

public class CallGraphGenerator {
    private static final Logger logger = Logger.getLogger(CallGraphGenerator.class.getName());
    private final Profile profile;
    private final List<String> stringTable;
    private final String outputPath;
    private Map<String, Long> functionSelfTime;
    private Map<String, Long> functionCumTime;
    private double secondsPerSample;

    public CallGraphGenerator(Profile profile, String outputPath) {
        this.profile = profile;
        this.stringTable = profile.getStringTableList();
        this.outputPath = outputPath;
    }

    public void generateCallGraph() throws IOException {
        // 构建调用关系图
        Map<String, Set<String>> callGraph = buildCallGraph();
        
        // 生成SVG调用图
        generateSvg(callGraph, this.functionCumTime, this.functionSelfTime);
    }

    public Map<String, Long> getFunctionSelfTime() {
        return functionSelfTime;
    }

    public Map<String, Long> getFunctionCumTime() {
        return functionCumTime;
    }

    public double getSecondsPerSample() {
        return secondsPerSample;
    }

    private Map<String, Set<String>> buildCallGraph() {
        List<Sample> samples = profile.getSampleList();
        
        // 尝试三种不同的方法来计算自身时间
        Map<String, Long> selfTimes1 = new HashMap<>();    // 方法1：叶子节点获得全部时间
        Map<String, Long> selfTimes2 = new HashMap<>();    // 方法2：每个函数获得样本的权重
        Map<String, Long> selfTimes3 = new HashMap<>();    // 方法3：只有调用栈顶部函数获得自身时间
        
        Map<String, Long> cumulativeTimes = new HashMap<>();  // 累积时间（样本数）
        Map<String, Set<String>> callGraph = new HashMap<>();
        
        // 计算总样本数和时间
        long totalSamples = 0;
        for (Sample sample : samples) {
            if (sample.getValueCount() > 0) {
                totalSamples += sample.getValueList().get(0);
            }
        }
        
        // 首先计算每个样本中函数的自身时间和累积时间
        for (Sample sample : samples) {
            if (sample.getLocationIdCount() > 0 && sample.getValueCount() > 0) {
                // 获取样本值 - 在pprof中，第一个值通常是样本数量，第二个值可能是累积时间
                long sampleValue = sample.getValueList().get(0); // 样本数量/权重
                
                // 构建调用栈
                List<String> callStack = new ArrayList<>();
                for (int i = 0; i < sample.getLocationIdCount(); i++) {
                    Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                    if (location.getLineCount() > 0) {
                        long functionId = location.getLine(0).getFunctionId();
                        Function function = profile.getFunction((int)functionId - 1);
                        String functionName = stringTable.get((int)function.getName());
                        callStack.add(functionName);
                    }
                }
                
                // 方法3：只有调用栈顶部的函数（索引0）获得自身时间
                if (!callStack.isEmpty()) {
                    String topFunction = callStack.get(0);
                    selfTimes3.merge(topFunction, sampleValue, Long::sum);
                }
                
                // 方法1：最后一个函数（叶子节点）获得全部自身时间
                if (!callStack.isEmpty()) {
                    String leafFunction = callStack.get(callStack.size() - 1);
                    selfTimes1.merge(leafFunction, sampleValue, Long::sum);
                }
                
                // 方法2：每个函数获得样本的权重(累积时间)
                for (String functionName : callStack) {
                    selfTimes2.merge(functionName, sampleValue, Long::sum);
                    cumulativeTimes.merge(functionName, sampleValue, Long::sum);
                }

                // 构建调用关系
                for (int i = 0; i < callStack.size() - 1; i++) {
                    String caller = callStack.get(i);
                    String callee = callStack.get(i + 1);
                    
                    // 避免自调用的环
                    if (!caller.equals(callee)) {
                        callGraph.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
                    }
                }
            }
        }

        // 计算总时间
        long totalTime = 0;
        for (Long time : cumulativeTimes.values()) {
            totalTime = Math.max(totalTime, time);
        }
        
        if (totalTime == 0) {
            logger.warning("警告: 累积时间总计为0，可能数据有问题");
            totalTime = 1; // 防止除以零
        }
        
        // 使用profile的period信息计算时间（与火焰图保持一致）
        long period = profile.getPeriod();
        String unit = stringTable.get((int)profile.getPeriodType().getUnit());
        double totalTimeNanos = unit.equals("nanoseconds") ? 
            totalSamples * period : totalSamples * period * 1000;
        double totalTimeSec = totalTimeNanos / 1_000_000_000.0;  // 转换为秒
        double secondsPerSample = totalTimeSec / totalSamples;
        
        logger.fine("总样本数: " + totalSamples);
        logger.fine("周期值(period): " + period);
        logger.fine("周期单位(unit): " + unit);
        logger.fine("计算得到的总时间: " + totalTimeSec + " 秒");
        logger.fine("每个样本的时间值: " + secondsPerSample + " 秒");
        
        // 过滤掉累积时间小于1秒的函数
        double threshold = 1.0 / secondsPerSample;  // 1秒对应的样本数
        logger.fine("过滤阈值: " + threshold + " 样本数 (1秒)");

        // 创建新的过滤后的调用图
        Map<String, Set<String>> filteredGraph = new HashMap<>();
        final long finalTotalTime = totalTime; // 创建一个 final 变量供所有 lambda 使用
        final double finalSecondsPerSample = secondsPerSample; // 创建一个 final 变量供所有 lambda 使用
        callGraph.forEach((caller, callees) -> {
            if (cumulativeTimes.getOrDefault(caller, 0L) >= threshold) {
                Set<String> significantCallees = callees.stream()
                    .filter(callee -> cumulativeTimes.getOrDefault(callee, 0L) >= threshold)
                    .collect(java.util.stream.Collectors.toSet());
                if (!significantCallees.isEmpty()) {
                    filteredGraph.put(caller, significantCallees);
                }
            }
        });

        // 存储时间信息到类成员变量
        // 自身时间使用方法3（顶部函数），累积时间使用方法2（全部样本）
        this.functionSelfTime = selfTimes3;
        this.functionCumTime = cumulativeTimes;
        this.secondsPerSample = finalSecondsPerSample;

        return filteredGraph;
    }

    private void generateSvg(Map<String, Set<String>> callGraph, Map<String, Long> functionCumTime,
            Map<String, Long> functionSelfTime) throws IOException {
        try (FileWriter writer = new FileWriter(outputPath)) {
            int width = 3000;
            int height = 1800;
            int padding = 40;
            
            // 计算总时间（使用最大的累积时间作为总时间）
            long totalTime = 0;
            for (Long time : functionCumTime.values()) {
                totalTime = Math.max(totalTime, time);
            }
            
            if (totalTime == 0) {
                logger.warning("警告: 生成SVG时发现累积时间总计为0，可能数据有问题");
                totalTime = 1; // 防止除以零
            }

            // 计算总时间（秒）
            long period = profile.getPeriod();
            String unit = stringTable.get((int)profile.getPeriodType().getUnit());
            double totalTimeNanos = unit.equals("nanoseconds") ? 
                totalTime * period : totalTime * period * 1000;
            double totalTimeSec = totalTimeNanos / 1_000_000_000.0;  // 转换为秒
            
            // 过滤掉累积时间小于1秒的函数
            double thresholdInSeconds = 1.0; // 过滤阈值为1秒
            long thresholdInSamples = (long)(thresholdInSeconds / (totalTimeSec / totalTime));
            final long finalTotalTime = totalTime; // 创建一个 final 变量供所有 lambda 使用
            
            logger.fine("SVG生成 - 过滤阈值: " + thresholdInSamples + " 样本数 (" + thresholdInSeconds + "秒)");
            
            // 找出符合阈值的函数
            Set<String> significantFunctions = functionCumTime.entrySet().stream()
                .filter(entry -> entry.getValue() >= thresholdInSamples)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
            
            logger.fine("符合阈值的函数数量: " + significantFunctions.size());

            // 更新调用图，只保留重要函数
            Map<String, Set<String>> filteredGraph = new HashMap<>();
            callGraph.forEach((caller, callees) -> {
                if (significantFunctions.contains(caller)) {
                    Set<String> filteredCallees = callees.stream()
                        .filter(significantFunctions::contains)
                        .collect(java.util.stream.Collectors.toSet());
                    if (!filteredCallees.isEmpty()) {
                        filteredGraph.put(caller, filteredCallees);
                    }
                }
            });

            // SVG头部和样式定义
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
            writer.write(String.format("<svg width=\"%d\" height=\"%d\" xmlns=\"http://www.w3.org/2000/svg\">\n",
                width, height));

            // 样式定义
            writer.write("<style>\n");
            writer.write(".node { fill: #ffffff; stroke: #d32f2f; stroke-width: 2px; }\n");
            writer.write(".node.hot { fill: #ffebee; stroke: #d32f2f; stroke-width: 3px; }\n");
            writer.write(".node-text { font-family: Arial; font-size: 14px; text-anchor: middle; }\n");
            writer.write(".node-package { font-family: Arial; font-size: 12px; fill: #666; text-anchor: middle; }\n");
            writer.write(".node-time { font-family: Arial; font-size: 12px; fill: #d32f2f; text-anchor: middle; }\n");
            writer.write(".edge { stroke: #d32f2f; stroke-width: 2px; fill: none; marker-end: url(#arrowhead); }\n");
            writer.write(".edge-label { font-family: Arial; font-size: 12px; fill: #d32f2f; text-anchor: middle; }\n");
            writer.write("</style>\n");

            // 箭头定义
            writer.write("<defs>\n");
            writer.write("  <marker id=\"arrowhead\" viewBox=\"0 -5 10 10\" refX=\"2\" refY=\"0\" \n");
            writer.write("          markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">\n");
            writer.write("    <path d=\"M0,-5L10,0L0,5\" fill=\"#d32f2f\"/>\n");
            writer.write("  </marker>\n");
            writer.write("</defs>\n");

            // 添加标题和信息
            writer.write(String.format("<text x=\"%d\" y=\"%d\" font-family=\"Arial\" font-size=\"18\" font-weight=\"bold\">函数调用图</text>\n", 
                padding, padding - 10));
            writer.write(String.format("<text x=\"%d\" y=\"%d\" font-family=\"Arial\" font-size=\"12\">总时间: %.2f秒</text>\n", 
                padding, padding + 10, totalTimeSec));

            // 计算节点位置
            Map<String, NodeInfo> nodeInfos = calculateHierarchicalLayout(filteredGraph, width, height, padding);

            // 绘制边
            for (Map.Entry<String, Set<String>> entry : filteredGraph.entrySet()) {
                String caller = entry.getKey();
                NodeInfo callerInfo = nodeInfos.get(caller);
                
                for (String callee : entry.getValue()) {
                    NodeInfo calleeInfo = nodeInfos.get(callee);
                    if (callerInfo != null && calleeInfo != null) {
                        // 交换起点和终点，使箭头指向下方
                        double startX = calleeInfo.x + calleeInfo.width / 2;
                        double startY = calleeInfo.y + calleeInfo.height;
                        double endX = callerInfo.x + callerInfo.width / 2;
                        double endY = callerInfo.y;

                        // 使用直线而不是贝塞尔曲线
                        writer.write(String.format("<path class=\"edge\" d=\"M%.1f,%.1f L%.1f,%.1f\"/>\n",
                            startX, startY, endX, endY));

                        // 在边的中间添加耗时标签 - 显示调用者(caller)的累积时间
                        double labelX = (startX + endX) / 2;
                        double labelY = (startY + endY) / 2 - 10;
                        long callerTime = functionCumTime.getOrDefault(caller, 0L);
                        double callerTimeNanos = unit.equals("nanoseconds") ? 
                            callerTime * period : callerTime * period * 1000;
                        double callerSecs = callerTimeNanos / 1_000_000_000.0;
                        double callerPercent = 100.0 * callerTime / finalTotalTime;
                        writer.write(String.format("<text class=\"edge-label\" x=\"%.1f\" y=\"%.1f\">%.2fs (%.2f%%)</text>\n",
                            labelX, labelY, callerSecs, callerPercent));
                    }
                }
            }

            // 绘制节点
            for (Map.Entry<String, NodeInfo> entry : nodeInfos.entrySet()) {
                String function = entry.getKey();
                NodeInfo info = entry.getValue();
                long cumTime = functionCumTime.getOrDefault(function, 0L);
                long selfTime = functionSelfTime.getOrDefault(function, 0L);
                
                // 计算百分比（使用总累积时间作为基准）
                double cumPercent = 100.0 * cumTime / finalTotalTime;
                double selfPercent = 100.0 * selfTime / finalTotalTime;
                
                // 转换为秒
                double selfTimeNanos = unit.equals("nanoseconds") ? 
                    selfTime * period : selfTime * period * 1000;
                double selfSecs = selfTimeNanos / 1_000_000_000.0;
                
                double cumTimeNanos = unit.equals("nanoseconds") ? 
                    cumTime * period : cumTime * period * 1000;
                double cumSecs = cumTimeNanos / 1_000_000_000.0;

                // 确定节点是否为热点
                String nodeClass = cumPercent > 10 ? "node hot" : "node";

                // 绘制节点矩形
                writer.write(String.format("<g transform=\"translate(%.1f,%.1f)\">\n", info.x, info.y));
                writer.write(String.format("<rect class=\"%s\" width=\"%.1f\" height=\"%.1f\" rx=\"4\"/>\n",
                    nodeClass, info.width, info.height));

                // 分割函数名并添加包名和函数名
                String[] nameParts = function.split("\\.");
                double textY = 15;
                if (nameParts.length > 1) {
                    // 显示包名
                    writer.write(String.format("<text class=\"node-package\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, nameParts[0]));
                    textY += 15;
                    // 显示函数名
                    writer.write(String.format("<text class=\"node-text\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, nameParts[1]));
                } else {
                    // 只显示函数名
                    writer.write(String.format("<text class=\"node-text\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, function));
                }

                // 添加性能信息
                textY += 15;
                writer.write(String.format("<text class=\"node-time\" x=\"%.1f\" y=\"%.1f\">flat: %.2fs (%.2f%%)</text>\n",
                    info.width / 2, textY, selfSecs, selfPercent));
                textY += 15;
                writer.write(String.format("<text class=\"node-time\" x=\"%.1f\" y=\"%.1f\">cum: %.2fs (%.2f%%)</text>\n",
                    info.width / 2, textY, cumSecs, cumPercent));
                writer.write("</g>\n");
            }

            writer.write("</svg>\n");
        }
    }

    private static class NodeInfo {
        double x, y;
        double width, height;
        int level;
        
        NodeInfo(double x, double y, double width, double height, int level) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.level = level;
        }
    }

    private Map<String, NodeInfo> calculateHierarchicalLayout(Map<String, Set<String>> callGraph,
            int width, int height, int padding) {
        Map<String, NodeInfo> nodeInfos = new HashMap<>();
        Map<String, Integer> levels = new HashMap<>();
        
        // 找到根节点（入度为0的节点）
        Set<String> allNodes = new HashSet<>();
        Set<String> calledNodes = new HashSet<>();
        callGraph.forEach((caller, callees) -> {
            allNodes.add(caller);
            calledNodes.addAll(callees);
            allNodes.addAll(callees);
        });
        
        Set<String> rootNodes = new HashSet<>(allNodes);
        rootNodes.removeAll(calledNodes);

        // 如果没有找到根节点，使用任意一个节点作为根节点
        if (rootNodes.isEmpty() && !allNodes.isEmpty()) {
            rootNodes.add(allNodes.iterator().next());
        }
        
        // 计算每个节点的层级
        for (String root : rootNodes) {
            calculateLevels(root, 0, callGraph, levels, new HashSet<>());
        }

        // 按层级对节点进行分组
        Map<Integer, List<String>> levelGroups = new HashMap<>();
        levels.forEach((node, level) -> 
            levelGroups.computeIfAbsent(level, k -> new ArrayList<>()).add(node));
        
        // 计算每层的节点位置
        int maxLevel = levelGroups.keySet().stream().max(Integer::compare).orElse(0);
        double levelHeight = (height - 2.0 * padding) / (maxLevel + 1);
        double nodeWidth = 140.0;  // 减小节点宽度
        double nodeHeight = 70.0;  // 减小节点高度
        double verticalSpacing = 70.0; // 垂直间距

        // 为每一层的节点分配位置，但翻转层级顺序
        levelGroups.forEach((level, nodes) -> {
            // 计算这一层所有节点的总宽度和间距
            double totalWidth = nodes.size() * nodeWidth;
            double horizontalSpacing = (width - totalWidth) / (nodes.size() + 1);
            
            // 翻转Y轴坐标计算：maxLevel - level 使得最低层级显示在最上面
            double yPos = padding + (maxLevel - level) * (nodeHeight + verticalSpacing);
            
            // 对这一层的每个节点进行定位
            for (int i = 0; i < nodes.size(); i++) {
                String node = nodes.get(i);
                double x = padding + horizontalSpacing * (i + 1) + nodeWidth * i;
                nodeInfos.put(node, new NodeInfo(x, yPos, nodeWidth, nodeHeight, level));
            }
        });

        return nodeInfos;
    }

    private void calculateLevels(String node, int level, Map<String, Set<String>> callGraph,
            Map<String, Integer> levels, Set<String> visited) {
        if (visited.contains(node)) {
            return;
        }
        visited.add(node);
        
        // 更新节点的层级为当前找到的最大层级
        levels.merge(node, level, Math::max);
        
        // 递归处理所有子节点
        Set<String> callees = callGraph.getOrDefault(node, Collections.emptySet());
        for (String callee : callees) {
            calculateLevels(callee, level + 1, callGraph, levels, visited);
        }
    }
}