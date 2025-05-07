# 性能分析演示项目

这个项目演示了使用Go语言生成性能分析数据，并用Java程序解析和可视化这些数据的整体流程。

## 项目结构

```
project-root/
├── go-service/          # Go HTTP 服务，集成 SkyWalking 和 pprof
│   ├── main.go          # Go服务主文件，创建CPU密集型负载
│   └── go.mod/go.sum    # Go依赖管理文件
├── java-analyzer/       # Java 程序，用于解析 pprof 生成的分析文件
│   ├── src/             # Java源代码目录
│   │   └── main/java/com/example/ # Java主包
│   │       ├── PprofAnalyzer.java         # 主解析程序
│   │       ├── FlameGraphGenerator.java   # 火焰图生成器
│   │       └── CallGraphGenerator.java    # 调用图生成器
│   └── pom.xml          # Maven配置文件
├── profiling-data/      # 存储 pprof 生成的性能分析文件
│   └── cpu.prof         # CPU性能分析数据
└── README.md            # 项目说明文档
```

## 使用说明

### 生成性能分析数据

1. 切换到Go服务目录
   ```
   cd go-service
   ```

2. 启动Go服务
   ```
   go run main.go
   ```

3. 服务将自动生成CPU性能分析数据并保存到`profiling-data/cpu.prof`

### 解析和可视化性能分析数据

1. 切换到Java分析器目录
   ```
   cd java-analyzer
   ```

2. 使用Maven编译和运行分析器
   ```
   mvn clean compile exec:java
   ```

3. 程序会解析`profiling-data/cpu.prof`文件，并生成以下可视化文件：
   - flamegraph.svg：CPU性能火焰图
   - callgraph.svg：函数调用关系图

## 技术说明

- Go服务使用内置的pprof包生成性能分析数据
- Java分析器使用Google的Protocol Buffers来解析pprof格式的分析数据
- 可视化使用SVG格式，可以在任何现代浏览器中查看 