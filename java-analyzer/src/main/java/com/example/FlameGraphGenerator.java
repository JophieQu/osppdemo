package com.example;

import com.google.perftools.profiles.ProfileProto.Profile;
import com.google.perftools.profiles.ProfileProto.Function;
import com.google.perftools.profiles.ProfileProto.Location;
import com.google.perftools.profiles.ProfileProto.Sample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

public class FlameGraphGenerator {
    private final Profile profile;
    private final List<String> stringTable;
    private final String outputPath;

    public FlameGraphGenerator(Profile profile, String outputPath) {
        this.profile = profile;
        this.stringTable = profile.getStringTableList();
        this.outputPath = outputPath;
    }

    public void generateFlameGraph() throws IOException {
        // 生成折叠格式的堆栈信息
        List<StackInfo> stackInfos = generateStackInfos();
        
        // 生成SVG火焰图
        generateSvg(stackInfos);
    }

    private List<StackInfo> generateStackInfos() {
        List<StackInfo> stackInfos = new ArrayList<>();
        List<Sample> samples = profile.getSampleList();

        for (Sample sample : samples) {
            long count = sample.getValueList().get(0);
            StringBuilder stack = new StringBuilder();
            List<String> frames = new ArrayList<>();

            // 从底部到顶部构建调用栈
            for (int i = sample.getLocationIdCount() - 1; i >= 0; i--) {
                Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    frames.add(functionName);
                }
            }

            // 构建折叠格式的堆栈字符串
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) stack.append(";");
                stack.append(frames.get(i));
            }

            stackInfos.add(new StackInfo(stack.toString(), count));
        }

        return stackInfos;
    }

    private void generateSvg(List<StackInfo> stackInfos) throws IOException {
        // 合并相同堆栈的样本数
        Map<String, Long> stackCounts = new HashMap<>();
        for (StackInfo info : stackInfos) {
            stackCounts.merge(info.stack, info.count, Long::sum);
        }

        // 计算总样本数和最大堆栈深度
        long totalSamples = stackCounts.values().stream().mapToLong(Long::longValue).sum();
        int maxDepth = stackCounts.keySet().stream()
            .mapToInt(stack -> stack.split(";").length)
            .max()
            .orElse(0);

        // 生成SVG
        try (FileWriter writer = new FileWriter(outputPath)) {
            int width = 1200;
            int frameHeight = 24;
            int height = (maxDepth + 1) * frameHeight;
            int xpad = 10;
            int titleHeight = 80;

            writer.write(String.format("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"));
            writer.write(String.format("<svg width=\"%d\" height=\"%d\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\">\n",
                width + 2 * xpad, height + titleHeight));

            // 添加标题
            writer.write(String.format("<text x=\"%d\" y=\"20\" font-size=\"14\" font-family=\"Verdana\">%s</text>\n",
                xpad, "CPU Flame Graph"));

            writer.write(String.format("<defs>\n"));
            writer.write(String.format("  <linearGradient id=\"grad\" x1=\"0%%\" y1=\"0%%\" x2=\"100%%\" y2=\"0%%\">\n"));
            writer.write(String.format("    <stop offset=\"0%%\" style=\"stop-color:#FF6B6B;stop-opacity:1\" />\n"));
            writer.write(String.format("    <stop offset=\"50%%\" style=\"stop-color:#4ECDC4;stop-opacity:1\" />\n"));
            writer.write(String.format("    <stop offset=\"100%%\" style=\"stop-color:#45B7D1;stop-opacity:1\" />\n"));
            writer.write(String.format("  </linearGradient>\n"));
            writer.write(String.format("</defs>\n"));

            // 添加标题和说明
            writer.write(String.format("<style>\n"));
            writer.write(String.format(".title { font-size: 20px; font-weight: bold; font-family: Arial; }\n"));
            writer.write(String.format(".subtitle { font-size: 14px; font-family: Arial; fill: #666; }\n"));
            writer.write(String.format(".frame { transition: opacity 0.3s; cursor: pointer; }\n"));
            writer.write(String.format(".frame:hover { opacity: 0.8; }\n"));
            writer.write(String.format(".frame-text { font-size: 14px; font-family: Arial; pointer-events: none; font-weight: 500; text-shadow: 1px 1px 2px rgba(0,0,0,0.5); }\n"));
            writer.write(String.format("</style>\n"));

            // 计算CPU性能统计信息
            long period = profile.getPeriod();
            String unit = stringTable.get((int)profile.getPeriodType().getUnit());
            double totalTimeNanos = unit.equals("nanoseconds") ? 
                totalSamples * period : totalSamples * period * 1000;
            double totalTimeMs = totalTimeNanos / 1_000_000.0;
            double samplingRate = totalSamples / (totalTimeMs / 1000.0);
            
            // 添加标题和性能统计信息
            writer.write(String.format("<text x=\"%d\" y=\"24\" class=\"title\">CPU Profile Flame Graph</text>\n", xpad));
            writer.write(String.format("<text x=\"%d\" y=\"45\" class=\"subtitle\">总采样数: %d | 函数数: %d | 总CPU时间: %.2f ms | 采样率: %.1f Hz</text>\n",
                xpad, totalSamples, stackCounts.size(), totalTimeMs, samplingRate));
            
            // 添加热点函数统计
            Map<String, Long> hotFunctions = new HashMap<>();
            for (Map.Entry<String, Long> entry : stackCounts.entrySet()) {
                String[] frames = entry.getKey().split(";");
                for (String frame : frames) {
                    hotFunctions.merge(frame, entry.getValue(), Long::sum);
                }
            }
            
            // 获取前5个最热点的函数
            List<Map.Entry<String, Long>> hotList = new ArrayList<>(hotFunctions.entrySet());
            hotList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            StringBuilder hotSpots = new StringBuilder("热点函数: ");
            for (int i = 0; i < Math.min(5, hotList.size()); i++) {
                Map.Entry<String, Long> hot = hotList.get(i);
                if (i > 0) hotSpots.append(" | ");
                hotSpots.append(String.format("%s (%.1f%%)", 
                    truncateText(hot.getKey(), 30),
                    100.0 * hot.getValue() / totalSamples));
            }
            
            writer.write(String.format("<text x=\"%d\" y=\"65\" class=\"subtitle\">%s</text>\n",
                xpad, hotSpots.toString()));

            // 绘制火焰图框架
            double xscale = (double) width / totalSamples;
            for (Map.Entry<String, Long> entry : stackCounts.entrySet()) {
                String[] frames = entry.getKey().split(";");
                long samples = entry.getValue();
                double frameWidth = samples * xscale;

                for (int i = 0; i < frames.length; i++) {
                    double x = xpad + stackCounts.entrySet().stream()
                        .filter(e -> e.getKey().compareTo(entry.getKey()) < 0)
                        .mapToLong(Map.Entry::getValue)
                        .sum() * xscale;
                    double y = height + titleHeight - (i + 1) * frameHeight;

                    // 使用渐变色填充
                    writer.write(String.format("<g class=\"frame\" data-samples=\"%d\">\n", samples));
                    writer.write(String.format("<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%d\" "
                        + "fill=\"url(#grad)\" opacity=\"%.2f\" rx=\"2\" ry=\"2\" stroke=\"#FFF\" stroke-width=\"0.5\">\n",
                        x, y, frameWidth, frameHeight - 1, 0.8 + (0.2 * i / frames.length)));
                    double frameDurationMs = (samples * totalTimeMs) / totalSamples;
                    writer.write(String.format("<title>%s\n采样数: %d (%.2f%%)\n耗时: %.2f ms</title>\n</rect>\n",
                        frames[i], samples, 100.0 * samples / totalSamples, frameDurationMs));

                    // 添加文本标签
                    if (frameWidth > 50) {
                        String displayText = String.format("%s (%.1f ms)", 
                            truncateText(frames[i], frameWidth - 50), frameDurationMs);
                        writer.write(String.format("<text x=\"%.1f\" y=\"%.1f\" class=\"frame-text\" fill=\"#FFF\">%s</text>\n",
                            x + 3, y + frameHeight - 8, displayText));
                    }
                    writer.write("</g>\n");
                }
            }

            writer.write("</svg>\n");
        }
    }

    private String truncateText(String text, double width) {
        int maxChars = (int)(width / 6); // 假设每个字符平均宽度为6像素
        if (text.length() > maxChars) {
            return text.substring(0, maxChars - 3) + "...";
        }
        return text;
    }

    private static class StackInfo {
        final String stack;
        final long count;

        StackInfo(String stack, long count) {
            this.stack = stack;
            this.count = count;
        }
    }
}