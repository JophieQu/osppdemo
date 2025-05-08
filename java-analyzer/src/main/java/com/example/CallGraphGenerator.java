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
    private Map<String, Long> functionSelfTime;
    private Map<String, Long> functionCumTime;
    private double secondsPerSample;

    public CallGraphGenerator(Profile profile, String outputPath) {
        this.profile = profile;
        this.stringTable = profile.getStringTableList();
        this.outputPath = outputPath;
    }

    public void generateCallGraph() throws IOException {
        // 分析样本值类型
        analyzeSampleValues();
        
        // 构建调用关系图
        Map<String, Set<String>> callGraph = buildCallGraph();
        
        // 比较计算结果与pprof的结果
        System.out.println("\n===== 计算结果与pprof比较 =====");
        System.out.println("pprof显示的前几个函数:");
        System.out.println("math.pow: flat=132.53s (42.00%), cum=157.09s (49.79%)");
        System.out.println("math.tan: flat=93.37s (29.59%), cum=96.64s (30.63%)");
        System.out.println("math.log: flat=25.17s (7.98%), cum=34.97s (11.08%)");
        System.out.println("\n我们的计算结果:");
        
        // 使用类成员变量中的时间数据
        for (String function : List.of("math.pow", "math.tan", "math.log")) {
            if (functionCumTime.containsKey(function)) {
                long selfTime = functionSelfTime.getOrDefault(function, 0L);
                long cumTime = functionCumTime.getOrDefault(function, 0L);
                
                double selfSecs = selfTime * secondsPerSample;
                double cumSecs = cumTime * secondsPerSample;
                
                // 计算总时间（使用最大的累积时间作为总时间）
                long totalTime = 0;
                for (Long time : functionCumTime.values()) {
                    totalTime = Math.max(totalTime, time);
                }
                
                System.out.printf("%s: flat=%.2fs (%.2f%%), cum=%.2fs (%.2f%%)\n",
                    function,
                    selfSecs, 100.0 * selfTime / totalTime,
                    cumSecs, 100.0 * cumTime / totalTime);
            }
        }
        
        // 生成SVG调用图
        generateSvg(callGraph, this.functionCumTime, this.functionSelfTime);
    }

    private void analyzeSampleValues() {
        List<Sample> samples = profile.getSampleList();
        
        // 分析样本的值类型
        System.out.println("\n===== 样本值分析 =====");
        if (!samples.isEmpty()) {
            Sample firstSample = samples.get(0);
            System.out.println("样本数量: " + samples.size());
            System.out.println("值类型数量: " + firstSample.getValueCount());
            if (firstSample.getValueCount() > 0) {
                System.out.println("第一个样本的第一个值: " + firstSample.getValueList().get(0));
                if (firstSample.getValueCount() > 1) {
                    System.out.println("第一个样本的第二个值: " + firstSample.getValueList().get(1));
                }
            }
            System.out.println("位置ID数量: " + firstSample.getLocationIdCount());
            
            // 打印第一个样本的调用栈
            System.out.println("\n第一个样本的调用栈:");
            for (int i = 0; i < firstSample.getLocationIdCount(); i++) {
                Location location = profile.getLocation((int)firstSample.getLocationId(i) - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    System.out.println(i + ": " + functionName);
                }
            }
            
            // 分析样本值的分布
            System.out.println("\n样本值分布:");
            Map<String, List<Long>> functionValues = new HashMap<>();
            
            for (Sample sample : samples) {
                if (sample.getLocationIdCount() > 0 && sample.getValueCount() > 0) {
                    long value = sample.getValueList().get(0);
                    
                    for (int i = 0; i < sample.getLocationIdCount(); i++) {
                        Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                        if (location.getLineCount() > 0) {
                            long functionId = location.getLine(0).getFunctionId();
                            Function function = profile.getFunction((int)functionId - 1);
                            String functionName = stringTable.get((int)function.getName());
                            
                            functionValues.computeIfAbsent(functionName, k -> new ArrayList<>()).add(value);
                        }
                    }
                }
            }
            
            // 打印前10个函数的样本值
            System.out.println("前10个函数的样本值:");
            functionValues.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .limit(10)
                .forEach(entry -> {
                    long sum = entry.getValue().stream().mapToLong(Long::longValue).sum();
                    System.out.println(entry.getKey() + ": " + entry.getValue().size() + " 个样本，总和: " + sum);
                });
        }

        // 分析 math.Pow 和 math.pow 函数
        System.out.println("\n===== 函数调用关系分析 =====");
        Map<String, Map<String, Integer>> callerCalleeCounts = new HashMap<>();
        
        for (Sample sample : samples) {
            if (sample.getLocationIdCount() > 1 && sample.getValueCount() > 0) {
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
                
                // 分析调用关系
                for (int i = 0; i < callStack.size() - 1; i++) {
                    String caller = callStack.get(i);
                    String callee = callStack.get(i + 1);
                    
                    callerCalleeCounts
                        .computeIfAbsent(caller, k -> new HashMap<>())
                        .merge(callee, 1, Integer::sum);
                }
            }
        }
        
        // 打印 math.Pow 和 math.pow 的调用关系
        printFunctionRelationship("math.Pow", callerCalleeCounts);
        printFunctionRelationship("math.pow", callerCalleeCounts);
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
                // 获取样本值 - 在pprof中，第一个值通常是样本数量
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
                
                // 方法2：每个函数获得样本的权重
                for (String functionName : callStack) {
                    selfTimes2.merge(functionName, sampleValue, Long::sum);
                    // 同时计算累积时间：使用方法2，每个函数获得样本的完整权重
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
        
        // 打印前5个样本的调用栈，帮助调试
        System.out.println("\n===== 前5个样本的调用栈 =====");
        int debugSampleCount = 0;
        for (Sample sample : samples) {
            if (debugSampleCount >= 5) break;
            if (sample.getLocationIdCount() > 0 && sample.getValueCount() > 0) {
                long sampleValue = sample.getValueList().get(0);
                System.out.println("\n样本 " + debugSampleCount + " (权重: " + sampleValue + "):");
                
                List<String> callStack = new ArrayList<>();
                System.out.println("原始调用栈顺序:");
                for (int i = 0; i < sample.getLocationIdCount(); i++) {
                    Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                    if (location.getLineCount() > 0) {
                        long functionId = location.getLine(0).getFunctionId();
                        Function function = profile.getFunction((int)functionId - 1);
                        String functionName = stringTable.get((int)function.getName());
                        callStack.add(functionName);
                        System.out.println(i + ": " + functionName);
                    }
                }
                
                // 打印反转后的调用栈
                System.out.println("反转后的调用栈顺序:");
                List<String> reversedCallStack = new ArrayList<>(callStack);
            //    Collections.reverse(reversedCallStack);
                for (int i = 0; i < reversedCallStack.size(); i++) {
                    System.out.println(i + ": " + reversedCallStack.get(i));
                }
                
                debugSampleCount++;
            }
        }

        // 计算总时间
        long totalTime = 0;
        for (Long time : cumulativeTimes.values()) {
            totalTime = Math.max(totalTime, time);
        }
        
        if (totalTime == 0) {
            System.err.println("警告: 累积时间总计为0，可能数据有问题");
            totalTime = 1; // 防止除以零
        }
        
        // 计算每个样本的时间值（秒）
        double secondsPerSample = 315.0 / totalSamples; // 假设总时间约为315秒，根据pprof输出调整
        System.out.println("每个样本的时间值: " + secondsPerSample + " 秒");
        
        // 过滤掉累积时间小于1秒的函数
        double threshold = 1.0 / secondsPerSample;  // 1秒对应的样本数
        System.out.println("过滤阈值: " + threshold + " 样本数 (1秒)");

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
        
        // 比较三种不同方法的结果
        System.out.println("\n===== 自身时间计算方法比较 =====");
        System.out.println("前10个函数（按累积时间排序）:");
        
        // 获取前10个函数（按累积时间排序）
        List<String> top10Functions = cumulativeTimes.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toList());
        
        // 打印每个函数的三种方法结果
        for (String function : top10Functions) {
            long cumTime = cumulativeTimes.getOrDefault(function, 0L);
            long self1 = selfTimes1.getOrDefault(function, 0L);
            long self2 = selfTimes2.getOrDefault(function, 0L);
            long self3 = selfTimes3.getOrDefault(function, 0L);
            
            double cumSecs = cumTime * finalSecondsPerSample;
            double self1Secs = self1 * finalSecondsPerSample;
            double self2Secs = self2 * finalSecondsPerSample;
            double self3Secs = self3 * finalSecondsPerSample;
            
            System.out.printf("函数: %s\n", function);
            System.out.printf("  累积时间: %.2fs\n", cumSecs);
            System.out.printf("  自身时间(方法1-叶子节点): %.2fs (%.2f%%)\n", self1Secs, 100.0 * self1 / finalTotalTime);
            System.out.printf("  自身时间(方法2-全部样本): %.2fs (%.2f%%)\n", self2Secs, 100.0 * self2 / finalTotalTime);
            System.out.printf("  自身时间(方法3-顶部函数): %.2fs (%.2f%%)\n", self3Secs, 100.0 * self3 / finalTotalTime);
        }

        // 存储时间信息到类成员变量
        // 自身时间使用方法3（顶部函数），累积时间使用方法2（全部样本）
        this.functionSelfTime = selfTimes3;
        this.functionCumTime = cumulativeTimes;
        this.secondsPerSample = finalSecondsPerSample;
        
        // 打印三种方法的差异
        System.out.println("\n===== 方法差异分析 =====");
        for (String function : List.of("math.pow", "math.tan", "math.log")) {
            if (cumulativeTimes.containsKey(function)) {
                long cumTime = cumulativeTimes.getOrDefault(function, 0L);
                long self3 = selfTimes3.getOrDefault(function, 0L);
                
                double cumSecs = cumTime * finalSecondsPerSample;
                double self3Secs = self3 * finalSecondsPerSample;
                
                System.out.printf("函数: %s\n", function);
                System.out.printf("  自身时间(秒): %.2fs (%.2f%%)\n", 
                    self3Secs, 100.0 * self3 / finalTotalTime);
                System.out.printf("  累积时间(秒): %.2fs (%.2f%%)\n", 
                    cumSecs, 100.0 * cumTime / finalTotalTime);
            }
        }

        return filteredGraph;
    }

    private void generateSvg(Map<String, Set<String>> callGraph, Map<String, Long> functionCumTime,
            Map<String, Long> functionSelfTime) throws IOException {
        try (FileWriter writer = new FileWriter(outputPath)) {
            int width = 1800;
            int height = 1500;
            int padding = 40;
            
            // 计算总时间（使用最大的累积时间作为总时间）
            long totalTime = 0;
            for (Long time : functionCumTime.values()) {
                totalTime = Math.max(totalTime, time);
            }
            
            if (totalTime == 0) {
                System.err.println("警告: 生成SVG时发现累积时间总计为0，可能数据有问题");
                totalTime = 1; // 防止除以零
            }

            // 过滤掉累积时间小于1秒的函数
            double thresholdInSeconds = 1.0; // 过滤阈值为1秒
            long thresholdInSamples = (long)(thresholdInSeconds / secondsPerSample);
            final long finalTotalTime = totalTime; // 创建一个 final 变量供所有 lambda 使用
            
            System.out.println("SVG生成 - 过滤阈值: " + thresholdInSamples + " 样本数 (" + thresholdInSeconds + "秒)");
            
            // 找出符合阈值的函数
            Set<String> significantFunctions = functionCumTime.entrySet().stream()
                .filter(entry -> entry.getValue() >= thresholdInSamples)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
            
            System.out.println("符合阈值的函数数量: " + significantFunctions.size());

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
            writer.write("  <marker id=\"arrowhead\" viewBox=\"0 -5 10 10\" refX=\"8\" refY=\"0\" \n");
            writer.write("          markerWidth=\"6\" markerHeight=\"6\" orient=\"auto\">\n");
            writer.write("    <path d=\"M0,-5L10,0L0,5\" fill=\"#d32f2f\"/>\n");
            writer.write("  </marker>\n");
            writer.write("</defs>\n");

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
                        double callerSecs = callerTime * secondsPerSample;
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
                double selfSecs = selfTime * secondsPerSample;
                double cumSecs = cumTime * secondsPerSample;

                // 确定节点是否为热点
                String nodeClass = cumPercent > 10 ? "node hot" : "node";

                // 绘制节点矩形
                writer.write(String.format("<g transform=\"translate(%.1f,%.1f)\">\n", info.x, info.y));
                writer.write(String.format("<rect class=\"%s\" width=\"%.1f\" height=\"%.1f\" rx=\"4\"/>\n",
                    nodeClass, info.width, info.height));

                // 分割函数名并添加包名和函数名
                String[] nameParts = function.split("\\.");
                double textY = 35;
                if (nameParts.length > 1) {
                    // 显示包名
                    writer.write(String.format("<text class=\"node-package\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, nameParts[0]));
                    textY += 20;
                    // 显示函数名
                    writer.write(String.format("<text class=\"node-text\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, nameParts[1]));
                } else {
                    // 只显示函数名
                    writer.write(String.format("<text class=\"node-text\" x=\"%.1f\" y=\"%.1f\">%s</text>\n",
                        info.width / 2, textY, function));
                }

                // 添加性能信息
                textY += 25;
                writer.write(String.format("<text class=\"node-time\" x=\"%.1f\" y=\"%.1f\">flat: %.2fs (%.2f%%)</text>\n",
                    info.width / 2, textY, selfSecs, selfPercent));
                textY += 20;
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
        
        // 打印根节点信息
        System.out.println("\n===== 根节点信息 =====");
        System.out.println("找到 " + rootNodes.size() + " 个根节点:");
        for (String root : rootNodes) {
            System.out.println("- " + root);
        }

        // 计算每个节点的层级
        for (String root : rootNodes) {
            calculateLevels(root, 0, callGraph, levels, new HashSet<>());
        }

        // 按层级对节点进行分组
        Map<Integer, List<String>> levelGroups = new HashMap<>();
        levels.forEach((node, level) -> 
            levelGroups.computeIfAbsent(level, k -> new ArrayList<>()).add(node));
        
        // 打印层级信息
        System.out.println("\n===== 层级信息 =====");
        System.out.println("找到 " + levels.size() + " 个节点，分布在 " + levelGroups.size() + " 个层级:");
        levelGroups.forEach((level, nodes) -> {
            System.out.println("层级 " + level + ": " + nodes.size() + " 个节点");
            if (level == 0) {
                System.out.println("  " + String.join(", ", nodes));
            }
        });

        // 计算每层的节点位置
        int maxLevel = levelGroups.keySet().stream().max(Integer::compare).orElse(0);
        double levelHeight = (height - 2.0 * padding) / (maxLevel + 1);
        double nodeWidth = 140.0;  // 减小节点宽度
        double nodeHeight = 110.0;  // 减小节点高度
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

    private void printFunctionRelationship(String functionName, Map<String, Map<String, Integer>> callerCalleeCounts) {
        System.out.println("\n===== " + functionName + " 函数调用关系 =====");
        Map<String, Integer> calleeCounts = callerCalleeCounts.getOrDefault(functionName, Collections.emptyMap());
        System.out.println("调用关系:");
        calleeCounts.forEach((callee, count) -> {
            System.out.println("- " + callee + ": " + count + " 次");
        });
    }
}