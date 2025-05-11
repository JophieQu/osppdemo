package com.example;

import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Location;
import com.google.perftools.profiles.ProfileProto.Sample;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PprofAnalyzer {
    private static final Logger logger = Logger.getLogger(PprofAnalyzer.class.getName());

    public static void main(String[] args) {
        String profilePath = "../profiling-data/cpu.prof";

        try (InputStream fileInputStream = new FileInputStream(profilePath);
             InputStream gzipInputStream = new GZIPInputStream(fileInputStream)) {

            // 解析 profiling 文件
            Profile profile = Profile.parseFrom(gzipInputStream);
            
            // 获取字符串表
            List<String> stringTable = profile.getStringTableList();

            // 生成火焰图
            String flameGraphPath = "../profiling-data/flamegraph.svg";
            FlameGraphGenerator flameGraphGenerator = new FlameGraphGenerator(profile, flameGraphPath);
            flameGraphGenerator.generateFlameGraph();
            logger.info("火焰图已生成到: " + flameGraphPath);

            // 生成调用图
            String callGraphPath = "../profiling-data/callgraph.svg";
            CallGraphGenerator callGraphGenerator = new CallGraphGenerator(profile, callGraphPath);
            callGraphGenerator.generateCallGraph();
            logger.info("调用图已生成到: " + callGraphPath);
            
            // 从CallGraphGenerator获取性能数据
            Map<String, Long> functionSelfTime = callGraphGenerator.getFunctionSelfTime(); 
            Map<String, Long> functionCumTime = callGraphGenerator.getFunctionCumTime();
            
            // 计算时间信息
            long period = profile.getPeriod();
            String unit = stringTable.get((int)profile.getPeriodType().getUnit());
            
            // 获取函数对象映射
            Map<String, Function> functionMap = new HashMap<>();
            for (int i = 0; i < profile.getFunctionCount(); i++) {
                Function function = profile.getFunction(i);
                String functionName = stringTable.get((int)function.getName());
                functionMap.put(functionName, function);
            }
            
            // 将函数转换为列表并按累积时间排序
            List<Map.Entry<String, Long>> sortedFunctions = new ArrayList<>(functionCumTime.entrySet());
            Collections.sort(sortedFunctions, (a, b) -> Long.compare(b.getValue(), a.getValue()));
            
            // 计算总时间
            long totalSamples = sortedFunctions.stream()
                .mapToLong(Map.Entry::getValue)
                .sum();
            double totalTimeNanos = unit.equals("nanoseconds") ? 
                totalSamples * period : totalSamples * period * 1000;
            double totalTimeSec = totalTimeNanos / 1_000_000_000.0;  // 转换为秒
            
            System.out.println(String.format("\nShowing nodes accounting for %.2fs, %.2f%% of %.2fs total", 
                totalTimeSec, 100.0, totalTimeSec));
            System.out.println("Showing top 10 nodes");
            System.out.println(String.format("%-10s %-8s %-8s %-10s %-8s %s", 
                "flat", "flat%", "sum%", "cum", "cum%", "name"));
            
            double sumPercent = 0;
            for (int i = 0; i < Math.min(10, sortedFunctions.size()); i++) {
                Map.Entry<String, Long> entry = sortedFunctions.get(i);
                String functionName = entry.getKey();
                long cumSamples = entry.getValue();
                
                // 从函数调用图获取自身时间
                long selfSamples = functionSelfTime.getOrDefault(functionName, 0L);
                
                // 计算时间和百分比
                double cumTimeNanos = unit.equals("nanoseconds") ? 
                    cumSamples * period : cumSamples * period * 1000;
                double cumTime = cumTimeNanos / 1_000_000_000.0;
                
                double selfTimeNanos = unit.equals("nanoseconds") ? 
                    selfSamples * period : selfSamples * period * 1000;
                double selfTime = selfTimeNanos / 1_000_000_000.0;
                
                double cumPercent = 100.0 * cumSamples / totalSamples;
                double selfPercent = 100.0 * selfSamples / totalSamples;
                
                sumPercent += selfPercent;
                
                System.out.println(String.format("%8.2fs %7.2f%% %7.2f%% %8.2fs %7.2f%% %s", 
                    selfTime, selfPercent, sumPercent, cumTime, cumPercent, functionName));
            }
        } catch (Exception e) {
            logger.severe("解析 profiling 文件时出错: " + e.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            logger.severe(sw.toString());
        }
    }
}
