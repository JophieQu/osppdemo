# 性能分析演示项目

这个项目演示了使用Go语言，生成性能分析数据，并用Java程序解析和可视化这些数据的整体流程。

## 项目结构

```
demo-root/
├── go-service/         # Go HTTP 程序，集成 SkyWalking 和 pprof
│   ├── main.go         # Go程序主文件，创建CPU密集型负载
│   └── go.mod/go.sum   # Go依赖管理文件
├── java-analyzer/      # Java 程序，用于解析 pprof 生成的分析文件
│   ├── src/            # Java源代码目录
│   │   └── main/java/com/example/ # Java主包
│   │       ├── PprofAnalyzer.java        # 主解析程序
│   │       ├── FlameGraphGenerator.java   # 火焰图生成器
│   │       └── CallGraphGenerator.java    # 调用图生成器
│   └── pom.xml         # Maven配置文件
├── profiling-data/     # 存储性能分析结果
│   ├── cpu.prof        # CPU性能分析数据
│   ├── flamegraph.svg  # 火焰图可视化结果
│   └── callgraph.svg   # 调用图可视化结果
└── README.md           # 项目说明文档
```

## 使用说明

## 启动SkyWalking服务

   ```
   > banyand standalone   # 启动banyanDB
   > bin/oapService.sh    # OAP启动
   > bin/webappService.sh # UI启动
   ```

### 生成性能分析数据

1. 切换到Go服务目录
   ```
   cd go-service
   ```

2. 启动Go服务并挂载skywalking
   ```
   > go build -toolexec="/Users/jingyiqu/ospp/demo/apache-skywalking-go-0.6.0-bin/bin/skywalking-go-agent-0.6.0-darwin-amd64" -a -o test .

   > export SW_AGENT_NAME=demo

   > ./test

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

3. 程序会解析`cpu.prof`文件，并生成以下可视化文件，保存到`profiling-data`目录下：
   - flamegraph.svg：CPU性能火焰图
   - callgraph.svg：函数调用关系图
   - 终端输出根据 cum 排序的前十个 HotSpot
