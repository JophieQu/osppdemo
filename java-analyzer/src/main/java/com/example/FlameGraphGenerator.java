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
import java.util.Collection;
import java.util.logging.Logger;
import java.util.logging.Level;

public class FlameGraphGenerator {
    private static final Logger logger = Logger.getLogger(FlameGraphGenerator.class.getName());
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
            List<String> callStack = new ArrayList<>();

            // 从叶子节点（调用栈底部）到根节点（调用栈顶部）构建调用栈
            // 在pprof中，LocationId列表的顺序是从叶子节点到根节点
            for (int i = 0; i < sample.getLocationIdCount(); i++) {
                Location location = profile.getLocation((int)sample.getLocationId(i) - 1);
                if (location.getLineCount() > 0) {
                    long functionId = location.getLine(0).getFunctionId();
                    Function function = profile.getFunction((int)functionId - 1);
                    String functionName = stringTable.get((int)function.getName());
                    callStack.add(functionName);
                }
            }

            // 构建折叠格式的堆栈字符串，注意：火焰图是自下而上展示的，所以顺序要反过来
            StringBuilder stackBuilder = new StringBuilder();
            for (int i = callStack.size() - 1; i >= 0; i--) {
                if (i < callStack.size() - 1) {
                    stackBuilder.append(";");
                }
                stackBuilder.append(callStack.get(i));
            }

            stackInfos.add(new StackInfo(stackBuilder.toString(), count));
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
        

        // 将调用栈信息转换为树形结构，方便生成火焰图
        FlameNode root = new FlameNode("root", 0);
        for (Map.Entry<String, Long> entry : stackCounts.entrySet()) {
            String[] frames = entry.getKey().split(";");
            long count = entry.getValue();
            
            // 将每个堆栈添加到树中
            FlameNode current = root;
            for (String frame : frames) {
                current = current.addChild(frame, count);
            }
        }

        // 生成SVG
        try (FileWriter writer = new FileWriter(outputPath)) {
            int width = 1200;  // 宽度设为1200px
            int frameHeight = 30;  // 框架高度设为30px
            int height = (maxDepth + 1) * frameHeight;
            int xpad = 10;
            int titleHeight = 100;
            
            // 确保高度足够
            height = Math.max(height, 500);

            // 简化SVG生成，使用最直接的方式写入XML
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
            writer.write("<svg width=\"" + (width + 2 * xpad) + "\" height=\"" + (height + titleHeight) + 
                "\" version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\">\n");

            // 定义红色系渐变色
            writer.write("<defs>\n");
            writer.write("<linearGradient id=\"grad\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"0%\">\n");
            writer.write("<stop offset=\"0%\" style=\"stop-color:#FF3D00;stop-opacity:1\"/>\n");
            writer.write("<stop offset=\"50%\" style=\"stop-color:#FF5252;stop-opacity:1\"/>\n");
            writer.write("<stop offset=\"100%\" style=\"stop-color:#FF8A80;stop-opacity:1\"/>\n");
            writer.write("</linearGradient>\n");
            
            // 为不同深度的节点定义不同的红色系渐变色
            for (int i = 1; i <= 10; i++) {
                writer.write("<linearGradient id=\"grad" + i + "\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"0%\">\n");
                writer.write("<stop offset=\"0%\" style=\"stop-color:#FF" + (3*i) + "00;stop-opacity:0.8\"/>\n");
                writer.write("<stop offset=\"100%\" style=\"stop-color:#FF" + (5*i) + "00;stop-opacity:0.9\"/>\n");
                writer.write("</linearGradient>\n");
            }
            writer.write("</defs>\n");

            // CSS样式定义
            writer.write("<style>\n");
            writer.write(".title { font-size: 18px; font-weight: bold; font-family: Arial; }\n");
            writer.write(".subtitle { font-size: 12px; font-family: Arial; fill: #666; }\n");
            writer.write(".frame { transition: opacity 0.3s; cursor: pointer; }\n");
            writer.write(".frame:hover { opacity: 0.8; }\n");
            writer.write(".frame-text { font-size: 10px; font-family: Arial; pointer-events: none; font-weight: 500; text-shadow: 0px 0px 3px rgba(0,0,0,0.7); fill: white; }\n");
            writer.write(".frame-time { font-size: 8px; font-family: Arial; fill: #FFF; text-shadow: 0px 0px 2px rgba(0,0,0,0.8); }\n");
            writer.write("</style>\n");

            // 计算CPU性能统计信息
            long period = profile.getPeriod();
            String unit = stringTable.get((int)profile.getPeriodType().getUnit());
            double totalTimeNanos = unit.equals("nanoseconds") ? 
                totalSamples * period : totalSamples * period * 1000;
            double totalTimeSec = totalTimeNanos / 1_000_000_000.0;  // 转换为秒
            double samplingRate = totalSamples / totalTimeSec;
            
            // 添加标题和性能统计信息 - 直接使用拼接避免格式问题
            writer.write("<text x=\"" + xpad + "\" y=\"24\" class=\"title\">CPU Profile Flame Graph</text>\n");
            writer.write("<text x=\"" + xpad + "\" y=\"42\" class=\"subtitle\">总采样数: " + totalSamples + 
                " | 函数数: " + profile.getFunctionCount() + 
                " | 总CPU时间: " + String.format("%.2f", totalTimeSec) + " s" +
                " | 采样率: " + String.format("%.1f", samplingRate) + " Hz</text>\n");
            writer.write("<text x=\"" + xpad + "\" y=\"58\" class=\"subtitle\">最大堆栈深度: " + maxDepth + 
                " | 不同堆栈数: " + stackCounts.size() + "</text>\n");
            
            // 添加热点函数统计
            Map<String, Long> hotFunctions = new HashMap<>();
            for (Map.Entry<String, Long> entry : stackCounts.entrySet()) {
                String[] frames = entry.getKey().split(";");
                for (String frame : frames) {
                    hotFunctions.merge(frame, entry.getValue(), Long::sum);
                }
            }
            
            // 前5个最热点的函数
            List<Map.Entry<String, Long>> hotList = new ArrayList<>(hotFunctions.entrySet());
            hotList.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            StringBuilder hotSpots = new StringBuilder("热点函数: ");
            for (int i = 0; i < Math.min(5, hotList.size()); i++) {
                Map.Entry<String, Long> hot = hotList.get(i);
                if (i > 0) hotSpots.append(" | ");
                double functionTimeSec = (hot.getValue() * totalTimeSec) / totalSamples;
                hotSpots.append(escapeXml(hot.getKey()))
                       .append(" (")
                       .append(String.format("%.2f", functionTimeSec))
                       .append(" s, ")
                       .append(String.format("%.1f", 100.0 * hot.getValue() / totalSamples))
                       .append("%)");
            }
            
            writer.write("<text x=\"" + xpad + "\" y=\"74\" class=\"subtitle\">" + hotSpots.toString() + "</text>\n");

            // 计算火焰图布局并绘制
            double xscale = (double) width / totalSamples;
            renderFlameGraph(writer, root, xpad, height + titleHeight - frameHeight, xscale, totalSamples, totalTimeSec);

            writer.write("</svg>\n");
        }
    }

    // 递归渲染火焰图的每个层级
    private void renderFlameGraph(FileWriter writer, FlameNode node, double x, double y, 
                                 double xscale, long totalSamples, double totalTimeSec) throws IOException {
        
        if (node.getName().equals("root")) {
            // 根节点不绘制，直接处理子节点
            double childX = x;
            
            // 对子节点按值从大到小排序，使主要函数出现在中间位置
            List<FlameNode> sortedChildren = new ArrayList<>(node.getChildren());
            sortedChildren.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            
            for (FlameNode child : sortedChildren) {
                double childWidth = child.getValue() * xscale;
                renderFlameGraph(writer, child, childX, y, xscale, totalSamples, totalTimeSec);
                childX += childWidth;
            }
            return;
        }

        // 框架高度固定为30像素
        double frameHeight = 30.0;
        
        // 计算自身宽度和位置
        double frameWidth = node.getValue() * xscale;
        
        // 根据深度选择不同的颜色
        int depth = getNodeDepth(node);
        int colorIndex = depth % 10 + 1;
        
        // 使用极简方式生成SVG元素
        writer.write("<g class=\"frame\">\n");
        
        // 矩形
        writer.write("<rect ");
        writer.write("x=\"" + x + "\" ");
        writer.write("y=\"" + y + "\" ");
        writer.write("width=\"" + frameWidth + "\" ");
        writer.write("height=\"" + (frameHeight - 1) + "\" ");
        writer.write("fill=\"url(#grad" + colorIndex + ")\" ");
        writer.write("stroke=\"rgba(150,50,50,0.3)\" ");
        writer.write("stroke-width=\"0.5\" ");
        writer.write("rx=\"2\" ");
        writer.write("ry=\"2\" ");
        writer.write(">\n");
        
        // 标题提示
        double frameDurationSec = (node.getValue() * totalTimeSec) / totalSamples;
        writer.write("<title>");
        writer.write(escapeXml(node.getName()) + "\n");
        writer.write("采样数: " + node.getValue() + " (" + 
            String.format("%.2f", 100.0 * node.getValue() / totalSamples) + "%)\n");
        writer.write("耗时: " + String.format("%.2f", frameDurationSec) + " s");
        writer.write("</title>\n");
        writer.write("</rect>\n");

        // 添加文本标签
        if (frameWidth > 25) {  // 减少最小宽度要求，让更多框架显示文本
            // 分两行显示：第一行是函数名，第二行是时间
            writer.write("<text x=\"" + (x + 3) + "\" y=\"" + (y + frameHeight - 14) + 
                "\" class=\"frame-text\">" + escapeXml(node.getName()) + "</text>\n");
            writer.write("<text x=\"" + (x + 3) + "\" y=\"" + (y + frameHeight - 3) + 
                "\" class=\"frame-time\">(" + String.format("%.2f", frameDurationSec) + " s)</text>\n");
        }
        writer.write("</g>\n");
        
        // 递归绘制子节点
        if (!node.getChildren().isEmpty()) {
            double childX = x;
            
            // 对子节点按值从大到小排序
            List<FlameNode> sortedChildren = new ArrayList<>(node.getChildren());
            sortedChildren.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            
            for (FlameNode child : sortedChildren) {
                double childWidth = child.getValue() * xscale;
                renderFlameGraph(writer, child, childX, y - frameHeight, xscale, totalSamples, totalTimeSec);
                childX += childWidth;
            }
        }
    }

    // 转义XML特殊字符
    private String escapeXml(String input) {
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }

    // 计算节点深度
    private int getNodeDepth(FlameNode node) {
        int depth = 0;
        FlameNode current = node;
        while (current != null && current.getParent() != null) {
            depth++;
            current = current.getParent();
        }
        return depth;
    }

    // 火焰图节点类，用于构建树形结构
    private static class FlameNode {
        private final String name;
        private long value;
        private final Map<String, FlameNode> children = new HashMap<>();
        private FlameNode parent;
        
        public FlameNode(String name, long value) {
            this.name = name;
            this.value = value;
        }
        
        public String getName() {
            return name;
        }
        
        public long getValue() {
            return value;
        }
        
        public Collection<FlameNode> getChildren() {
            return children.values();
        }
        
        public FlameNode getParent() {
            return parent;
        }
        
        public FlameNode addChild(String name, long increment) {
            FlameNode child = children.computeIfAbsent(name, k -> new FlameNode(k, 0));
            child.value += increment;
            child.parent = this;
            return child;
        }
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