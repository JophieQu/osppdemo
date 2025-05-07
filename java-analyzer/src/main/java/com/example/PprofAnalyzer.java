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

public class PprofAnalyzer {

    public static void main(String[] args) {
        String profilePath = "../profiling-data/cpu.prof"; // 使用相对路径指向分析文件

        try (InputStream fileInputStream = new FileInputStream(profilePath);
             InputStream gzipInputStream = new GZIPInputStream(fileInputStream)) {

            // 解析 profiling 文件
            Profile profile = Profile.parseFrom(gzipInputStream);

            // 显示一些基本信息
            System.out.println("样本数量: " + profile.getSampleCount());
            System.out.println("函数数量: " + profile.getFunctionCount());
            System.out.println("位置数量: " + profile.getLocationCount());
            System.out.println("------");

            // 显示字符串表内容
            List<String> stringTable = profile.getStringTableList();
            System.out.println("字符串表大小: " + stringTable.size());
            for (int i = 0; i < Math.min(20, stringTable.size()); i++) {
                System.out.println(String.format("[%d] '%s'", i, stringTable.get(i)));
            }
            System.out.println("------");

            // 显示前几个函数的信息
            List<Function> functions = profile.getFunctionList();
            for (int i = 0; i < Math.min(5, functions.size()); i++) {
                Function function = functions.get(i);
                int nameIdx = (int)function.getName();
                int sysNameIdx = (int)function.getSystemName();
                int fileIdx = (int)function.getFilename();
                
                System.out.println(String.format("函数 #%d:", i));
                System.out.println(String.format("  函数名[%d]: '%s'", nameIdx, 
                    nameIdx < stringTable.size() ? stringTable.get(nameIdx) : "<invalid>"));
                System.out.println(String.format("  系统名[%d]: '%s'", sysNameIdx,
                    sysNameIdx < stringTable.size() ? stringTable.get(sysNameIdx) : "<invalid>"));
                System.out.println(String.format("  文件名[%d]: '%s'", fileIdx,
                    fileIdx < stringTable.size() ? stringTable.get(fileIdx) : "<invalid>"));
                System.out.println(String.format("  开始行号: %d", function.getStartLine()));
                System.out.println("------");
            }

            // 分析样本数据并按照累积时间排序
            List<Sample> samples = profile.getSampleList();
            List<Sample> sortedSamples = new ArrayList<>(samples);
            Collections.sort(sortedSamples, (a, b) -> {
                long cumA = a.getValueList().get(1);
                long cumB = b.getValueList().get(1);
                return Long.compare(cumB, cumA); // 降序排序
            });

            System.out.println("按累积时间排序的样本数据:");
            for (int i = 0; i < Math.min(5, sortedSamples.size()); i++) {
                Sample sample = sortedSamples.get(i);
                System.out.println(String.format("样本 #%d: [count=%d, cum=%dns]", 
                    i, sample.getValueList().get(0), sample.getValueList().get(1)));
                System.out.println("位置:");
                
                // 显示调用栈信息
                for (long locationId : sample.getLocationIdList()) {
                    Location location = profile.getLocation((int)locationId - 1);
                    if (location.getLineCount() > 0) {
                        long functionId = location.getLine(0).getFunctionId();
                        Function function = profile.getFunction((int)functionId - 1);
                        String functionName = stringTable.get((int)function.getName());
                        String fileName = stringTable.get((int)function.getFilename());
                        System.out.println(String.format("  %s (%s:%d)", 
                            functionName, fileName, location.getLine(0).getLine()));
                    }
                }
                System.out.println("------");
            }

            // 创建函数的累积时间映射
            Map<Function, Long> functionCumTime = new HashMap<>();
            for (Sample sample : samples) {
                long cum = sample.getValueList().get(1);
                for (long locationId : sample.getLocationIdList()) {
                    Location location = profile.getLocation((int)locationId - 1);
                    if (location.getLineCount() > 0) {
                        long functionId = location.getLine(0).getFunctionId();
                        Function function = profile.getFunction((int)functionId - 1);
                        functionCumTime.merge(function, cum, Long::sum);
                    }
                }
            }

            // 将函数转换为列表并按累积时间排序
            List<Map.Entry<Function, Long>> sortedFunctions = new ArrayList<>(functionCumTime.entrySet());
            Collections.sort(sortedFunctions, (a, b) -> Long.compare(b.getValue(), a.getValue()));

            // 计算总时间
            long totalTime = sortedFunctions.stream().mapToLong(Map.Entry::getValue).sum();

            System.out.println(String.format("\nShowing nodes accounting for %.2fs, %.2f%% of %.2fs total", 
                totalTime / 1e9, 100.0, totalTime / 1e9));
            System.out.println("Showing top 5 nodes");
            System.out.println(String.format("%-10s %-8s %-8s %-10s %-8s %s", 
                "flat", "flat%", "sum%", "cum", "cum%", "name"));

            double sumPercent = 0;
            for (int i = 0; i < Math.min(5, sortedFunctions.size()); i++) {
                Map.Entry<Function, Long> entry = sortedFunctions.get(i);
                Function function = entry.getKey();
                long cumTime = entry.getValue();
                double cumPercent = 100.0 * cumTime / totalTime;
                sumPercent += cumPercent;

                String functionName = stringTable.get((int)function.getName());
                System.out.println(String.format("%8.2fs %7.2f%% %7.2f%% %8.2fs %7.2f%% %s", 
                    0.0, 0.0, sumPercent, cumTime / 1e9, cumPercent, functionName));
            }
            // 生成火焰图
            String flameGraphPath = "../profiling-data/flamegraph.svg";
            FlameGraphGenerator flameGraphGenerator = new FlameGraphGenerator(profile, flameGraphPath);
            flameGraphGenerator.generateFlameGraph();
            System.out.println("\n火焰图已生成到: " + flameGraphPath);

            // 生成调用图
            String callGraphPath = "../profiling-data/callgraph.svg";
            CallGraphGenerator callGraphGenerator = new CallGraphGenerator(profile, callGraphPath);
            callGraphGenerator.generateCallGraph();
            System.out.println("调用图已生成到: " + callGraphPath);
        } catch (Exception e) {
            System.err.println("解析 profiling 文件时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
